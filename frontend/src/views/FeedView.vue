<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：交接台双栏 + 投料批闭环卡片流。
// 左栏是可交接磅单（余量水位条，已验收且有余量才可点）；
// 右栏是压榨机组（能力占用槽，只有「运行」可点）；
// 中间交接单实时算出两侧上限的较小者，超任一侧都红字拦截。
// 提交成功后下方投料批卡片沿「待投料 → 投料中 → 已完成」推进：
// 待投料可撤回（两侧余量同时归还），投料中只能完成，完成按实际量结算。
// 每次提交都带「交接单号」：重复点击、超时重试沿用同一单号，只落一笔。
// ------------------------------------------------------------------

const intakes = ref([]) // 已验收磅单（含可投余量）
const presses = ref([]) // 全部机组（运行中才可交接）
const batches = ref([])
const error = ref('')
const tip = ref('')
const retryHint = ref('')

const ticketId = ref(null)
const stationId = ref(null)
const planKg = ref(5000)
const createdBy = ref('')
const requestId = ref(newRequestId())
const submitting = ref(false)
const busyId = ref(null) // 行内操作中的投料批
const actualDraft = ref({})

function newRequestId() {
  if (window.crypto && window.crypto.randomUUID) return window.crypto.randomUUID()
  return 'REQ-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10)
}

const ticket = computed(() => intakes.value.find((i) => i.id === ticketId.value) || null)
const station = computed(() => presses.value.find((p) => p.id === stationId.value) || null)

// 磅单侧：可投余量（kg）；机组侧：剩余能力（kg，设计 t/h × 1000 - 已占用 kg）
const ticketRemaining = computed(() => (ticket.value ? Number(ticket.value.remainingKg) : 0))
const stationFreeKg = computed(() =>
  station.value ? Number(station.value.designCapacityTph) * 1000 - Number(station.value.occupiedKg) : 0)
// 本单上限 = 两侧剩余的较小值；任一侧不足都不能提交
const planLimit = computed(() => Math.max(0, Math.min(ticketRemaining.value, stationFreeKg.value)))
const overTicket = computed(() => !!ticket.value && Number(planKg.value) > ticketRemaining.value)
const overStation = computed(() => !!station.value && Number(planKg.value) > stationFreeKg.value)
const canSubmit = computed(() =>
  !!ticket.value && !!station.value && Number(planKg.value) > 0
  && !overTicket.value && !overStation.value && !submitting.value)

async function load() {
  error.value = ''
  try {
    const [ins, ps, bs] = await Promise.all([
      api.listIntakes({ status: '已验收' }),
      api.listPresses(),
      api.listFeedBatches()
    ])
    intakes.value = ins
    presses.value = ps
    batches.value = bs
    if (!ticketId.value || !ins.some((i) => i.id === ticketId.value)) {
      const first = ins.find((i) => Number(i.remainingKg) > 0)
      ticketId.value = first ? first.id : null
    }
    if (!stationId.value || !ps.some((p) => p.id === stationId.value && p.status === '运行')) {
      const first = ps.find((p) => p.status === '运行')
      stationId.value = first ? first.id : null
    }
    const d = {}
    for (const b of bs) d[b.id] = b.actualKg != null ? b.actualKg : b.planKg
    actualDraft.value = d
  } catch (e) { error.value = e.message }
}

function pickTicket(i) {
  if (Number(i.remainingKg) > 0) ticketId.value = i.id
}

function pickStation(p) {
  if (p.status === '运行') stationId.value = p.id
}

function isTimeout(e) {
  const m = (e && e.message) || ''
  return m.includes('timeout') || m.includes('Network Error') || m.includes('ECONNABORTED')
}

async function submit() {
  if (!canSubmit.value) return
  error.value = ''; tip.value = ''; retryHint.value = ''
  submitting.value = true
  try {
    const v = await api.createFeedBatch({
      requestId: requestId.value,
      intakeId: ticketId.value,
      stationId: stationId.value,
      planKg: Number(planKg.value),
      createdBy: createdBy.value
    })
    tip.value = v.idempotentHit
      ? `交接单号 ${v.requestId} 已受理过，系统未重复扣减（安全重试生效）`
      : `投料批 ${v.batchNo} 已交接：磅单余量与机组占用已同步扣减`
    requestId.value = newRequestId() // 成功后换发新单号；失败则保留原单号供安全重试
    await load()
  } catch (e) {
    if (isTimeout(e)) {
      error.value = '请求超时或网络异常，本次交接结果未知（可能已落账，也可能未落账）'
      retryHint.value = `可以安全重试：再次点击「提交交接」将沿用同一交接单号 ${requestId.value}，系统保证同一单号只落一笔有效占用，不会重复扣减。`
    } else {
      error.value = e.message
      retryHint.value = '本次未落下任何扣减或占用（事务已整体回滚），可以安全重试；重试沿用同一交接单号，不会重复扣减。'
    }
  } finally {
    submitting.value = false
  }
}

async function act(b, fn, okMsg) {
  error.value = ''; tip.value = ''; retryHint.value = ''
  busyId.value = b.id
  try {
    await fn()
    tip.value = okMsg
    await load()
  } catch (e) {
    error.value = e.message + '（本次未改动任何余量与占用，可安全重试）'
  } finally {
    busyId.value = null
  }
}

const start = (b) => act(b, () => api.startFeedBatch(b.id), `投料批 ${b.batchNo} 已开始投料`)
const withdraw = (b) => act(b, () => api.withdrawFeedBatch(b.id), `投料批 ${b.batchNo} 已撤回，磅单余量与机组能力已归还`)
const complete = (b) => act(b, () => api.completeFeedBatch(b.id, Number(actualDraft.value[b.id])), `投料批 ${b.batchNo} 已完成，差额已释放回两侧余量`)

function validActual(b) {
  const v = Number(actualDraft.value[b.id])
  return v > 0 && v <= Number(b.planKg)
}

const shortfall = (b) => Number(b.planKg) - Number(b.actualKg)
const countBy = (s) => batches.value.filter((b) => b.status === s).length
const fmt = (n) => Number(n).toLocaleString('zh-CN')

function pct(part, whole) {
  const w = Number(whole)
  if (!w) return '0%'
  return Math.max(0, Math.min(100, (Number(part) / w) * 100)) + '%'
}

// 状态步骤：待投料 → 投料中 → 已完成（已撤回单独标记）
const stepOn = (b, s) => {
  if (s === '待投料') return true
  if (s === '投料中') return b.status === '投料中' || b.status === '已完成'
  return b.status === '已完成'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">投料交接 · 交接台</span>
        <span class="panel-desc">左选磅单、右选机组；计划量不得超过磅单可投余量，也不得超过机组剩余能力</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="retryHint" class="retry-tip">{{ retryHint }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="desk">
        <div class="col">
          <div class="col-title">可交接磅单<span>可投净重</span></div>
          <div
            v-for="i in intakes" :key="i.id" class="tk"
            :class="{ on: i.id === ticketId, off: Number(i.remainingKg) <= 0 }"
            @click="pickTicket(i)"
          >
            <div class="tk-head"><b>{{ i.ticketNo }}</b><span>{{ i.supplier }}</span></div>
            <div class="tk-num">{{ fmt(i.remainingKg) }}<em> kg 可投</em></div>
            <div class="bar"><i :style="{ width: pct(i.allocatedKg, i.netKg) }"></i></div>
            <div class="tk-sub">净重 {{ fmt(i.netKg) }} kg · 已分配 {{ fmt(i.allocatedKg) }} kg</div>
          </div>
          <div v-if="!intakes.length" class="empty">暂无已验收磅单</div>
        </div>

        <div class="col">
          <div class="col-title">压榨机组<span>剩余能力 / 已占用</span></div>
          <div
            v-for="p in presses" :key="p.id" class="ps"
            :class="{ on: p.id === stationId, off: p.status !== '运行' }"
            @click="pickStation(p)"
          >
            <div class="tk-head"><b>{{ p.stationName }}</b><span>{{ p.status }}</span></div>
            <div class="tk-num">{{ p.remainingCapacityTph }}<em> t/h 剩余</em></div>
            <div class="bar occ"><i :style="{ width: pct(p.occupiedKg, Number(p.designCapacityTph) * 1000) }"></i></div>
            <div class="tk-sub">设计 {{ p.designCapacityTph }} t/h · 已占用 {{ p.occupiedTon }} t</div>
          </div>
          <div v-if="!presses.length" class="empty">暂无机组</div>
        </div>

        <div class="col form-col">
          <div class="col-title">交接单</div>
          <div class="sum">
            <div>来源磅单：<b>{{ ticket ? ticket.ticketNo : '未选' }}</b></div>
            <div>目标机组：<b>{{ station ? station.stationName : '未选' }}</b></div>
          </div>
          <label class="lb">计划投料量（kg）</label>
          <input v-model.number="planKg" type="number" min="1" class="fld wide" />
          <div class="limit">本单上限 {{ fmt(planLimit) }} kg（取磅单余量与机组剩余的较小值）</div>
          <div v-if="overTicket" class="warn">超过磅单可投余量 {{ fmt(ticketRemaining) }} kg</div>
          <div v-if="overStation" class="warn">超过机组剩余能力 {{ fmt(stationFreeKg) }} kg</div>
          <label class="lb">值班员</label>
          <input v-model="createdBy" class="fld wide" placeholder="当班值班员" />
          <div class="rid">交接单号：{{ requestId }}</div>
          <button class="act primary big" :disabled="!canSubmit" @click="submit">
            {{ submitting ? '提交中…' : '提交交接' }}
          </button>
          <div class="safe">提交即一次性扣减磅单余量并占用机组能力；任一步失败整体回滚，可安全重试，同一单号只落一笔。</div>
        </div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">投料批 · 闭环流转</span>
        <span class="panel-desc">待投料可撤回（归还两侧余量）；投料中不可撤回、不可换机组；完成按实际量结算，差额释放回两侧</span>
      </div>

      <div class="stat-row">
        <div class="stat"><div class="k">投料批</div><div class="v">{{ batches.length }}</div></div>
        <div class="stat"><div class="k">待投料</div><div class="v">{{ countBy('待投料') }}</div></div>
        <div class="stat"><div class="k">投料中</div><div class="v">{{ countBy('投料中') }}</div></div>
        <div class="stat"><div class="k">已完成</div><div class="v">{{ countBy('已完成') }}</div></div>
        <div class="stat"><div class="k">已撤回</div><div class="v">{{ countBy('已撤回') }}</div></div>
      </div>

      <div class="fb-list">
        <div v-for="b in batches" :key="b.id" class="fb" :class="{ dead: b.status === '已撤回' }">
          <div class="fb-top">
            <b>{{ b.batchNo }}</b>
            <span class="fb-route">{{ b.ticketNo }} → {{ b.stationName }}</span>
            <span class="fb-by">{{ b.createdBy }}</span>
          </div>
          <div class="steps">
            <span class="step" :class="{ done: stepOn(b, '待投料') }">待投料</span>
            <span class="step" :class="{ done: stepOn(b, '投料中') }">投料中</span>
            <span class="step" :class="{ done: stepOn(b, '已完成') }">已完成</span>
            <span v-if="b.status === '已撤回'" class="step cancelled">已撤回</span>
          </div>
          <div class="fb-nums">
            <span>计划 {{ fmt(b.planKg) }} kg</span>
            <span v-if="b.actualKg != null">实际 {{ fmt(b.actualKg) }} kg</span>
            <span v-if="b.status === '已完成' && shortfall(b) > 0" class="rel">差额 {{ fmt(shortfall(b)) }} kg 已释放回两侧</span>
          </div>
          <div class="fb-ops">
            <template v-if="b.status === '待投料'">
              <button class="act sm primary" :disabled="busyId === b.id" @click="start(b)">开始投料</button>
              <button class="act sm" :disabled="busyId === b.id" @click="withdraw(b)">撤回</button>
            </template>
            <template v-else-if="b.status === '投料中'">
              <input v-model.number="actualDraft[b.id]" type="number" min="1" :max="b.planKg" class="fld n" />
              <button class="act sm primary" :disabled="busyId === b.id || !validActual(b)" @click="complete(b)">完成投料</button>
            </template>
            <span v-else class="fb-st">{{ b.status }}</span>
          </div>
        </div>
        <div v-if="!batches.length" class="empty">还没有投料批，从上方交接台发起</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.retry-tip {
  background: #fff8e1; border: 1px solid #f0dfa0; color: #8d6e00;
  padding: 8px 12px; border-radius: 8px; margin-bottom: 10px; font-size: 13px;
}

/* ---------------- 交接台三栏 ---------------- */
.desk { display: grid; grid-template-columns: 1fr 1fr 1fr; gap: 12px; }
@media (max-width: 980px) { .desk { grid-template-columns: 1fr; } }

.col { border: 1px solid var(--line); border-radius: 9px; padding: 10px; background: #fdfefa; }
.col-title {
  font-size: 13px; font-weight: 800; color: var(--primary-deep);
  display: flex; justify-content: space-between; margin-bottom: 8px;
}
.col-title span { font-weight: 600; font-size: 11px; color: var(--muted); }

.tk, .ps {
  border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px;
  margin-bottom: 8px; cursor: pointer; background: #fff;
}
.tk:hover, .ps:hover { border-color: var(--primary-soft); }
.tk.on, .ps.on { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); }
.tk.off, .ps.off { opacity: 0.5; cursor: not-allowed; }

.tk-head { display: flex; justify-content: space-between; font-size: 12px; }
.tk-head b { color: var(--primary-deep); }
.tk-head span { color: var(--muted); }
.tk-num { font-size: 18px; font-weight: 800; color: var(--primary-deep); margin: 3px 0; }
.tk-num em { font-size: 11px; font-style: normal; font-weight: 600; color: var(--muted); }
.tk-sub { font-size: 11px; color: var(--muted); margin-top: 3px; }

/* 余量水位条 / 能力占用槽：已用部分着色，剩余留空 */
.bar { height: 8px; border-radius: 4px; background: #eef1dc; overflow: hidden; }
.bar i { display: block; height: 100%; background: repeating-linear-gradient(45deg, #cfd6a2, #cfd6a2 5px, #e0e5c0 5px, #e0e5c0 10px); }
.bar.occ i { background: linear-gradient(90deg, var(--primary-soft), var(--primary)); }

.empty { font-size: 12px; color: var(--muted); text-align: center; padding: 14px 0; }

/* ---------------- 交接单 ---------------- */
.form-col .sum { font-size: 12px; color: var(--muted); margin-bottom: 8px; line-height: 1.9; }
.form-col .sum b { color: var(--primary-deep); }
.lb { display: block; font-size: 12px; color: var(--muted); margin: 8px 0 4px; }
.fld.wide { width: 100%; }
.limit { font-size: 12px; color: var(--primary-deep); margin-top: 5px; font-weight: 700; }
.warn { font-size: 12px; color: #c62828; margin-top: 4px; }
.rid { font-size: 11px; color: var(--muted); margin: 10px 0 8px; word-break: break-all; }
.act.big { width: 100%; padding: 10px; font-size: 14px; }
.safe { font-size: 11px; color: var(--muted); margin-top: 8px; line-height: 1.6; }

/* ---------------- 投料批卡片流 ---------------- */
.fb-list { display: flex; flex-direction: column; gap: 8px; }
.fb { border: 1px solid var(--line); border-radius: 9px; padding: 10px 12px; background: #fff; }
.fb.dead { opacity: 0.55; }
.fb-top { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; }
.fb-top b { color: var(--primary-deep); font-size: 13px; }
.fb-route { font-size: 12px; color: var(--ink); }
.fb-by { font-size: 11px; color: var(--muted); margin-left: auto; }

.steps { display: flex; gap: 6px; margin: 8px 0; }
.step {
  font-size: 11px; padding: 2px 10px; border-radius: 10px;
  background: #f2f4e8; color: var(--muted); border: 1px solid var(--line);
}
.step.done { background: var(--primary); border-color: var(--primary); color: #fff; font-weight: 700; }
.step.cancelled { background: #eceff1; color: #78909c; border-color: #cfd8dc; text-decoration: line-through; }

.fb-nums { display: flex; gap: 14px; font-size: 12px; color: var(--muted); flex-wrap: wrap; }
.fb-nums .rel { color: #2e7d32; font-weight: 700; }
.fb-ops { display: flex; gap: 8px; align-items: center; margin-top: 8px; }
.fb-st { font-size: 12px; color: var(--muted); }
</style>
