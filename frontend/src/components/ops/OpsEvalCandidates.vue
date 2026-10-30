<script setup lang="ts">
import { Download, FileJson } from "@lucide/vue";
import { onMounted, ref, watch } from "vue";

import { formatTime, opsApi, type EvalCandidateRow, type EvalExport } from "@/api/ops";

const emit = defineEmits<{ "open-run": [runId: number] }>();
/** The operator console cannot open agent runs (that is the ops console), so it hides the link. */
const props = withDefaults(defineProps<{ showRunLinks?: boolean }>(), { showRunLinks: true });

const status = ref<"PENDING" | "APPROVED" | "REJECTED">("PENDING");
const rows = ref<EvalCandidateRow[]>([]);
const loading = ref(false);
const error = ref("");
const editing = ref<number | null>(null);
const needDraft = ref("");
const noteDraft = ref("");
const busy = ref<number | null>(null);
const exported = ref<EvalExport | null>(null);
const exporting = ref(false);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    rows.value = await opsApi.evalCandidates(status.value);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "加载失败";
  } finally {
    loading.value = false;
  }
}

function startApprove(row: EvalCandidateRow) {
  editing.value = row.id;
  needDraft.value = row.needText ?? "";
  noteDraft.value = "";
}

async function review(row: EvalCandidateRow, decision: "APPROVED" | "REJECTED") {
  busy.value = row.id;
  error.value = "";
  try {
    await opsApi.reviewEvalCandidate(
      row.id,
      decision,
      decision === "APPROVED" ? needDraft.value : undefined,
      noteDraft.value || undefined
    );
    editing.value = null;
    await load();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "操作失败";
  } finally {
    busy.value = null;
  }
}

async function exportNext() {
  exporting.value = true;
  error.value = "";
  try {
    exported.value = await opsApi.exportEvalSet();
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : "导出失败";
  } finally {
    exporting.value = false;
  }
}

function download(name: string, content: string) {
  const url = URL.createObjectURL(new Blob([content + "\n"], { type: "application/json" }));
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  URL.revokeObjectURL(url);
}

onMounted(load);
watch(status, load);
</script>

<template>
  <div class="ops-card">
    <div class="ops-card-head">
      <h2 class="ops-subtitle">评测候选（来自“不相关”反馈）</h2>
      <div class="ops-filters">
        <select v-model="status">
          <option value="PENDING">待审核</option>
          <option value="APPROVED">已采纳</option>
          <option value="REJECTED">已驳回</option>
        </select>
        <button type="button" class="ops-button" :disabled="exporting" @click="exportNext">
          <FileJson :size="15" />
          <span>生成下一版评测集</span>
        </button>
      </div>
    </div>
    <p class="ops-muted">
      学生每点一次“不相关”，就会生成一条候选：对这个需求，这个活动不应该出现在前 5 名。
      采纳前请检查需求原文，<b>删除个人信息</b>：采纳的用例会被提交到仓库（公开）。
      采纳后不会立刻改动评测集，需要“生成下一版”，再由开发者提交文件。
    </p>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <div v-if="exported" class="ops-export">
      <template v-if="exported.newCaseIds.length">
        <p>
          下一版：<b>{{ exported.evalSetVersion }}</b>（语料 {{ exported.corpusVersion }}）。
          新增 {{ exported.newCaseIds.length }} 条用例
          <template v-if="exported.addedEventIds.length">，并冻结 {{ exported.addedEventIds.length }} 个新活动 {{ exported.addedEventIds.join(", ") }}</template>。
          把文件放到 <code>backend/src/main/resources/retrieval-eval/</code> 后提交即可。
        </p>
        <div class="ops-toolbar-actions">
          <button type="button" class="ops-button" @click="download(exported.evalSetFileName, exported.evalSetJson)">
            <Download :size="15" /><span>{{ exported.evalSetFileName }}</span>
          </button>
          <button
            v-if="exported.corpusJson && exported.corpusFileName"
            type="button"
            class="ops-button"
            @click="download(exported.corpusFileName, exported.corpusJson)"
          >
            <Download :size="15" /><span>{{ exported.corpusFileName }}</span>
          </button>
        </div>
      </template>
      <p v-else>没有需要导出的新用例（已采纳的都已在当前评测集 {{ exported.evalSetVersion }} 里）。</p>
      <ul v-if="exported.warnings.length" class="ops-errors">
        <li v-for="warning in exported.warnings" :key="warning">{{ warning }}</li>
      </ul>
    </div>

    <table class="ops-table">
      <thead>
        <tr><th>时间</th><th>需求</th><th>被拒的活动</th><th>原因</th><th></th></tr>
      </thead>
      <tbody>
        <template v-for="row in rows" :key="row.id">
          <tr>
            <td>{{ formatTime(row.createdAt) }}<br /><small class="ops-muted">{{ row.userId }}</small></td>
            <td class="ops-wrap">{{ row.needText ?? "（无）" }}</td>
            <td>#{{ row.eventId }} {{ row.eventTitle ?? "" }}</td>
            <td class="ops-wrap">{{ row.reason || "（未填写）" }}</td>
            <td class="ops-actions">
              <button v-if="row.runId && props.showRunLinks" type="button" class="ops-link" @click="emit('open-run', row.runId)">看那次推荐</button>
              <template v-if="row.status === 'PENDING'">
                <button type="button" class="ops-button small" :disabled="busy === row.id" @click="startApprove(row)">采纳</button>
                <button type="button" class="ops-button small" :disabled="busy === row.id" @click="review(row, 'REJECTED')">驳回</button>
              </template>
              <span v-else class="ops-muted">
                {{ row.status === "APPROVED" ? (row.inCurrentEvalSet ? "已在评测集中" : "待导出") : "已驳回" }}
              </span>
            </td>
          </tr>
          <tr v-if="editing === row.id" class="ops-payload-row">
            <td colspan="5">
              <form class="ops-approve-form" @submit.prevent="review(row, 'APPROVED')">
                <label>
                  <span>用例需求（可改写，去掉个人信息）</span>
                  <textarea v-model="needDraft" rows="2" maxlength="500" required />
                </label>
                <label>
                  <span>审核备注（选填）</span>
                  <input v-model="noteDraft" maxlength="200" placeholder="例如：时间冲突类，召回不该把工作日晚上的活动排前面" />
                </label>
                <div class="ops-toolbar-actions">
                  <button type="submit" class="ops-button small" :disabled="busy === row.id">确认采纳</button>
                  <button type="button" class="ops-button small" @click="editing = null">取消</button>
                </div>
              </form>
            </td>
          </tr>
        </template>
        <tr v-if="!rows.length && !loading"><td colspan="5" class="ops-muted">没有候选</td></tr>
      </tbody>
    </table>
  </div>
</template>
