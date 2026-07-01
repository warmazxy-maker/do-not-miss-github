<script setup lang="ts">
import { CheckCircle2, ChevronLeft, ChevronRight, Flag, LoaderCircle, Plus, Trash2, X } from "@lucide/vue";
import { onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";

import { ApiError } from "@/api/client";
import { challengesApi } from "@/api/workspace";
import type { Challenge, ChallengePage } from "@/types/workspace";

const route = useRoute();
const router = useRouter();
const categories = ["技能挑战", "考试挑战", "身体挑战", "作品挑战", "生活挑战", "其他挑战"];
const page = ref<ChallengePage>({ content: [], page: 0, size: 6, totalElements: 0, totalPages: 0, first: true, last: true });
const status = ref("ACTIVE");
const loading = ref(true);
const modalOpen = ref(false);
const completeTarget = ref<Challenge | null>(null);
const busy = ref(false);
const notice = ref("");
const createForm = reactive({ title: "", category: "技能挑战", goal: "", description: "" });
const completeForm = reactive({ did: "", learned: "" });

onMounted(async () => {
  await load(0);
  if (route.query.action === "create") modalOpen.value = true;
});

async function load(index = 0) {
  loading.value = true;
  try { page.value = await challengesApi.page(status.value, index, 6); }
  catch (cause) { notice.value = text(cause, "挑战加载失败"); }
  finally { loading.value = false; }
}

async function create() {
  busy.value = true;
  try {
    await challengesApi.create(createForm);
    Object.assign(createForm, { title: "", category: "技能挑战", goal: "", description: "" });
    modalOpen.value = false;
    await router.replace("/student/challenges");
    await load(0);
  } catch (cause) { notice.value = text(cause, "创建失败"); }
  finally { busy.value = false; }
}

function openComplete(challenge: Challenge) {
  completeTarget.value = challenge;
  completeForm.did = "";
  completeForm.learned = "";
}

async function complete() {
  if (!completeTarget.value) return;
  busy.value = true;
  try {
    await challengesApi.complete(completeTarget.value.id, completeForm.did, completeForm.learned);
    completeTarget.value = null;
    await load(page.value.page);
    notice.value = "挑战已完成，并进入个人成就";
  } catch (cause) { notice.value = text(cause, "完成失败"); }
  finally { busy.value = false; }
}

async function cancel(challenge: Challenge) {
  busy.value = true;
  try { await challengesApi.cancel(challenge.id); await load(page.value.page); }
  catch (cause) { notice.value = text(cause, "取消失败"); }
  finally { busy.value = false; }
}

function date(value: string) {
  return new Intl.DateTimeFormat("zh-CN", { year: "numeric", month: "short", day: "numeric" }).format(new Date(value));
}

function text(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section class="challenges-workspace-vue">
    <header class="module-page-heading">
      <div><p class="eyebrow">Personal Challenges</p><h2>把没有活动可参加的日子，也变成成长</h2><p>自定义目标可以分多次安排进日程，只有主动完成挑战才会写入成就。</p></div>
      <button type="button" @click="modalOpen = true"><Plus :size="18" />创建挑战</button>
    </header>
    <div class="challenge-status-tabs">
      <button v-for="item in [['ACTIVE','进行中'],['COMPLETED','已完成'],['CANCELLED','已取消']]" :key="item[0]" type="button" :class="{ 'is-active': status === item[0] }" @click="status=item[0];load(0)">{{ item[1] }}</button>
    </div>
    <div v-if="loading" class="achievement-loading"><LoaderCircle class="spin" :size="24" />正在读取挑战</div>
    <div v-else-if="!page.content.length" class="module-empty"><Flag :size="30" /><h3>这里还没有挑战</h3><p>创建一个属于自己的目标。</p></div>
    <div v-else class="challenge-card-grid">
      <article v-for="challenge in page.content" :key="challenge.id">
        <div><span>{{ challenge.category }}</span><small>创建于 {{ date(challenge.createdAt) }}</small></div>
        <h3>{{ challenge.title }}</h3><strong>{{ challenge.goal }}</strong><p>{{ challenge.description }}</p>
        <footer v-if="challenge.status === 'ACTIVE'">
          <button type="button" @click="cancel(challenge)"><Trash2 :size="16" />取消</button>
          <button type="button" @click="openComplete(challenge)"><CheckCircle2 :size="16" />完成挑战</button>
        </footer>
        <div v-else-if="challenge.status === 'COMPLETED'" class="challenge-complete-note"><CheckCircle2 :size="17" />完成于 {{ date(challenge.completedAt!) }}</div>
      </article>
    </div>
    <nav v-if="page.totalPages > 1" class="events-pagination">
      <button :disabled="page.first" @click="load(page.page-1)"><ChevronLeft :size="17" />上一页</button><span>{{ page.page+1 }} / {{ page.totalPages }}</span><button :disabled="page.last" @click="load(page.page+1)">下一页<ChevronRight :size="17" /></button>
    </nav>
    <p v-if="notice" class="module-error">{{ notice }}</p>

    <Teleport to="body"><Transition name="overlay"><div v-if="modalOpen" class="simple-dialog-layer" @click.self="modalOpen=false">
      <form class="simple-form-dialog" @submit.prevent="create">
        <header><div><p class="eyebrow">Challenge</p><h3>创建挑战</h3></div><button type="button" title="关闭" @click="modalOpen=false"><X :size="19" /></button></header>
        <label><span>挑战名称</span><input v-model="createForm.title" required placeholder="一周从 Java 转 Go" /></label>
        <label><span>类型</span><select v-model="createForm.category"><option v-for="item in categories" :key="item">{{ item }}</option></select></label>
        <label><span>目标</span><input v-model="createForm.goal" required placeholder="完成语法学习并写一个小项目" /></label>
        <label><span>执行内容</span><textarea v-model="createForm.description" rows="5" required></textarea></label>
        <button class="dialog-primary" type="submit" :disabled="busy"><LoaderCircle v-if="busy" class="spin" :size="17" />创建挑战</button>
      </form>
    </div></Transition></Teleport>

    <Teleport to="body"><Transition name="overlay"><div v-if="completeTarget" class="simple-dialog-layer" @click.self="completeTarget=null">
      <form class="simple-form-dialog" @submit.prevent="complete">
        <header><div><p class="eyebrow">Reflection</p><h3>完成「{{ completeTarget.title }}」</h3></div><button type="button" title="关闭" @click="completeTarget=null"><X :size="19" /></button></header>
        <label><span>我做了什么</span><textarea v-model="completeForm.did" rows="4" placeholder="记录具体行动和产出"></textarea></label>
        <label><span>我学到了什么</span><textarea v-model="completeForm.learned" rows="4" placeholder="记录方法、理解和不足"></textarea></label>
        <button class="dialog-primary" type="submit" :disabled="busy"><CheckCircle2 :size="17" />确认完成</button>
      </form>
    </div></Transition></Teleport>
  </section>
</template>
