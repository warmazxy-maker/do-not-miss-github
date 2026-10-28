<script setup lang="ts">
import { CheckCircle2, LoaderCircle, RotateCcw, Save, Send } from "@lucide/vue";
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";

import { ApiError } from "@/api/client";
import { socialApi } from "@/api/workspace";
import type { OrganizerEventItem } from "@/types/events";

const categories = ["公益", "企业", "校内", "线上", "研究", "文化"];
const blank = {
  title: "", organizationName: "", category: "企业", startTime: "", endTime: "",
  location: "", content: "", benefitType: "技能经验", skill: "", moneyAmount: ""
};
const form = reactive({ ...blank });
const busy = ref(false);
const success = ref("");
const error = ref("");

const route = useRoute();
const router = useRouter();
/** Set when the page was opened from "编辑" on 我的事件 (?edit=<id>). */
const editing = ref<OrganizerEventItem | null>(null);
const editingPublished = computed(() => editing.value?.reviewStatus === "APPROVED");

onMounted(loadEditing);
watch(() => route.query.edit, loadEditing);

async function loadEditing() {
  const id = Number(route.query.edit);
  editing.value = null;
  if (!id) return;
  try {
    const event = (await socialApi.mine()).find((item) => item.id === id) ?? null;
    if (!event) { error.value = "找不到要编辑的活动"; return; }
    editing.value = event;
    Object.assign(form, {
      title: event.title, organizationName: event.organizationName, category: event.category,
      startTime: event.startTime.slice(0, 16), endTime: event.endTime.slice(0, 16),
      location: event.location, content: event.content, benefitType: event.benefitType,
      skill: event.skill ?? "", moneyAmount: event.moneyAmount == null ? "" : String(event.moneyAmount)
    });
  } catch (cause) { error.value = message(cause, "活动读取失败"); }
}

function reset() {
  Object.assign(form, blank);
  success.value = "";
  error.value = "";
  if (editing.value) router.replace({ query: {} });
}

/** submit=false keeps it as "已保存"; a published event always goes back to review when edited. */
async function save(submit: boolean) {
  busy.value = true; success.value = ""; error.value = "";
  const payload = {
    ...form,
    skill: form.benefitType === "金钱报酬" ? null : form.skill.trim() || null,
    moneyAmount: form.benefitType === "技能经验" ? null : Number(form.moneyAmount),
    submit
  };
  try {
    const event = editing.value
      ? await socialApi.update(editing.value.id, payload)
      : await socialApi.create(payload);
    success.value = event.reviewStatus === "PENDING_REVIEW"
      ? `「${event.title}」已提交审核${event.reviewRoute === "MANUAL" ? "，本次由运营人工审核" : "，活动质量 Agent 正在分析"}。`
      : `「${event.title}」已保存，可以在“我的事件”里继续修改或提交审核。`;
    if (editing.value) {
      await router.push("/social/events");
    } else {
      Object.assign(form, { ...blank, organizationName: form.organizationName });
    }
  } catch (cause) { error.value = message(cause, "保存失败"); }
  finally { busy.value = false; }
}

function message(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="social-publish-view">
    <header class="module-page-heading">
      <div>
        <p class="eyebrow">{{ editing ? "Edit" : "Publish" }}</p>
        <h2>{{ editing ? `编辑「${editing.title}」` : "发布一个值得参加的事件" }}</h2>
        <p>信息会先经过结构化抽取、质量分析和审核，再进入学生检索与推荐索引。可以先保存，稍后再提交审核。</p>
      </div>
      <button type="button" @click="reset"><RotateCcw :size="17" />{{ editing ? "取消编辑" : "清空" }}</button>
    </header>
    <p v-if="editing?.revisionNote" class="event-state-note is-revision">审核意见：{{ editing.revisionNote }}</p>
    <p v-if="editingPublished" class="event-state-note is-warning">
      这个活动已上架。保存修改后会重新进入审核，审核期间学生看到的是“已下架：活动重新上架审核中”。
    </p>
    <form class="social-publish-form" @submit.prevent="save(true)">
      <div class="form-grid-two">
        <label><span>事件名称</span><input v-model="form.title" required placeholder="地方商店街采访协助" /></label>
        <label><span>组织名称</span><input v-model="form.organizationName" required placeholder="公司 / NPO / 学校部门" /></label>
      </div>
      <div class="form-grid-three">
        <label><span>开始时间</span><input v-model="form.startTime" type="datetime-local" required /></label>
        <label><span>结束时间</span><input v-model="form.endTime" type="datetime-local" required /></label>
        <label><span>地点</span><input v-model="form.location" required placeholder="大阪 / 线上" /></label>
      </div>
      <label><span>分类</span><select v-model="form.category"><option v-for="item in categories" :key="item">{{ item }}</option></select></label>
      <label><span>活动内容</span><textarea v-model="form.content" rows="6" required placeholder="写清楚学生需要做什么、适合谁参加、预计产出是什么"></textarea></label>
      <fieldset>
        <legend>参与收益</legend>
        <label v-for="item in ['技能经验','金钱报酬','两者都有']" :key="item"><input v-model="form.benefitType" type="radio" :value="item" /><span>{{ item }}</span></label>
      </fieldset>
      <div class="form-grid-two">
        <label v-if="form.benefitType !== '金钱报酬'"><span>技能 / 经验</span><input v-model="form.skill" required placeholder="日语沟通、活动运营" /></label>
        <label v-if="form.benefitType !== '技能经验'"><span>报酬</span><input v-model="form.moneyAmount" type="number" min="0" required placeholder="5000" /></label>
      </div>
      <div class="publish-actions">
        <button v-if="!editingPublished" class="publish-save-button" type="button" :disabled="busy" @click="save(false)">
          <Save :size="18" />仅保存
        </button>
        <button class="publish-submit-button" type="submit" :disabled="busy">
          <LoaderCircle v-if="busy" class="spin" :size="18" /><Send v-else :size="18" />
          {{ busy ? "提交中" : editingPublished ? "保存并重新审核" : "保存并提交审核" }}
        </button>
      </div>
      <p v-if="success" class="publish-success"><CheckCircle2 :size="18" />{{ success }}</p>
      <p v-if="error" class="module-error">{{ error }}</p>
    </form>
  </section>
</template>
