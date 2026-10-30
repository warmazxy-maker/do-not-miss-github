<script setup lang="ts">
import { UserPlus } from "@lucide/vue";
import { onMounted, ref } from "vue";

import { formatTime, opsApi, type OperatorAccount } from "@/api/ops";

/**
 * admin_zero creates operator accounts (D3, G4). The initial password is shown once, here, and is
 * not kept anywhere in readable form; pass it to the operator and have them sign in with the username.
 */
const accounts = ref<OperatorAccount[]>([]);
const username = ref("");
const created = ref<{ username: string; initialPassword: string } | null>(null);
const busy = ref(false);
const error = ref("");

onMounted(load);

async function load() {
  try { accounts.value = await opsApi.operators(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "运营账号读取失败"; }
}

async function create() {
  busy.value = true;
  error.value = "";
  created.value = null;
  try {
    created.value = await opsApi.createOperator(username.value.trim());
    username.value = "";
    await load();
  } catch (cause) { error.value = cause instanceof Error ? cause.message : "创建失败"; }
  finally { busy.value = false; }
}
</script>

<template>
  <div class="ops-card">
    <div class="ops-card-head">
      <h2 class="ops-subtitle">运营账号</h2>
      <form class="ops-filters" @submit.prevent="create">
        <input v-model="username" placeholder="用户名（字母、数字、下划线）" pattern="[A-Za-z0-9_]{3,30}" required />
        <button type="submit" class="ops-button" :disabled="busy"><UserPlus :size="15" /><span>新建运营账号</span></button>
      </form>
    </div>
    <p class="ops-muted">运营账号只能在这里新建，不能把学生或组织账号改成运营。管理员账号不能通过任何页面创建。</p>
    <p v-if="error" class="ops-error">{{ error }}</p>
    <div v-if="created" class="ops-notice">
      <p>已创建 <b>{{ created.username }}</b>。初始密码只显示这一次，请复制后交给对方：</p>
      <p class="operator-password">{{ created.initialPassword }}</p>
      <button type="button" class="ops-button small" @click="created = null">我已保存</button>
    </div>
    <table class="ops-table">
      <thead><tr><th>用户名</th><th>用户 ID</th><th>创建时间</th></tr></thead>
      <tbody>
        <tr v-for="account in accounts" :key="account.publicUserId">
          <td>{{ account.username }}</td>
          <td class="ops-muted">{{ account.publicUserId }}</td>
          <td>{{ formatTime(account.createdAt) }}</td>
        </tr>
        <tr v-if="!accounts.length"><td colspan="3" class="ops-muted">还没有运营账号</td></tr>
      </tbody>
    </table>
  </div>
</template>
