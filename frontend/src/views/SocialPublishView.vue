<script setup lang="ts">
import { CheckCircle2, LoaderCircle, RotateCcw, Send } from "@lucide/vue";
import { reactive, ref } from "vue";

import { ApiError } from "@/api/client";
import { socialApi } from "@/api/workspace";

const categories = ["公益", "企业", "校内", "线上", "研究", "文化"];
const form = reactive({
  title: "", organizationName: "", category: "企业", startTime: "", endTime: "",
  location: "", content: "", benefitType: "技能经验", skill: "", moneyAmount: ""
});
const busy = ref(false);
const success = ref("");
const error = ref("");

function reset() {
  Object.assign(form, { title: "", organizationName: "", category: "企业", startTime: "", endTime: "", location: "", content: "", benefitType: "技能经验", skill: "", moneyAmount: "" });
  success.value = "";
  error.value = "";
}

async function submit() {
  busy.value = true; success.value = ""; error.value = "";
  try {
    const event = await socialApi.create({
      ...form,
      skill: form.benefitType === "金钱报酬" ? null : form.skill.trim() || null,
      moneyAmount: form.benefitType === "技能经验" ? null : Number(form.moneyAmount)
    });
    success.value = `「${event.title}」已提交，活动质量 Agent 正在分析，审核通过后会进入学生端。`;
    resetFields();
  } catch (cause) { error.value = cause instanceof ApiError || cause instanceof Error ? cause.message : "发布失败"; }
  finally { busy.value = false; }
}

function resetFields() {
  Object.assign(form, { title: "", category: "企业", startTime: "", endTime: "", location: "", content: "", benefitType: "技能经验", skill: "", moneyAmount: "" });
}
</script>

<template>
  <section class="social-publish-view">
    <header class="module-page-heading">
      <div><p class="eyebrow">Publish</p><h2>发布一个值得参加的事件</h2><p>信息会先经过结构化抽取、质量分析和审核，再进入学生检索与推荐索引。</p></div>
      <button type="button" @click="reset"><RotateCcw :size="17" />清空</button>
    </header>
    <form class="social-publish-form" @submit.prevent="submit">
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
      <button class="publish-submit-button" type="submit" :disabled="busy"><LoaderCircle v-if="busy" class="spin" :size="18" /><Send v-else :size="18" />{{ busy ? "提交中" : "提交审核" }}</button>
      <p v-if="success" class="publish-success"><CheckCircle2 :size="18" />{{ success }}</p>
      <p v-if="error" class="module-error">{{ error }}</p>
    </form>
  </section>
</template>
