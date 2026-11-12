<script setup lang="ts">
import { RefreshCw } from "@lucide/vue";
import { onMounted, ref } from "vue";

import { opsApi, type AgentToolCatalog } from "@/api/ops";

/** F0: the tool and agent registries, read-only. Definitions live in code; this only shows them. */
const data = ref<AgentToolCatalog | null>(null);
const loading = ref(false);
const error = ref("");
const expanded = ref<string | null>(null);

onMounted(load);

async function load() {
  loading.value = true;
  error.value = "";
  try { data.value = await opsApi.agentTools(); }
  catch (cause) { error.value = cause instanceof Error ? cause.message : "工具注册表读取失败"; }
  finally { loading.value = false; }
}

function toggle(name: string) {
  expanded.value = expanded.value === name ? null : name;
}
</script>

<template>
  <section class="ops-section">
    <div class="ops-toolbar">
      <span class="ops-muted">工具定义写在代码里，这里只读。临时关闭某个工具：在 <code>.env</code> 的 <code>AGENT_DISABLED_TOOLS</code> 里写工具名，重启后端。</span>
      <div class="ops-toolbar-actions">
        <button type="button" class="ops-button" :disabled="loading" @click="load"><RefreshCw :size="15" /><span>刷新</span></button>
      </div>
    </div>
    <p v-if="error" class="ops-error">{{ error }}</p>

    <div class="ops-card">
      <h2 class="ops-subtitle">Agent（{{ data?.agents.length ?? 0 }}）</h2>
      <div class="ops-table-wrap">
        <table class="ops-table">
          <thead><tr><th>名称</th><th>提示词版本</th><th>每轮最多步数</th><th>限流档</th><th>能用的工具</th></tr></thead>
          <tbody>
            <tr v-for="agent in data?.agents ?? []" :key="agent.name">
              <td><strong>{{ agent.title }}</strong> <span class="ops-muted">{{ agent.name }}</span></td>
              <td>{{ agent.promptVersion }}</td>
              <td>{{ agent.maxSteps }}</td>
              <td>{{ agent.rateLimitTier }}</td>
              <td><span v-for="tool in agent.tools" :key="tool" class="ops-chip">{{ tool }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div class="ops-card">
      <h2 class="ops-subtitle">工具（{{ data?.tools.length ?? 0 }}）</h2>
      <div class="ops-table-wrap">
        <table class="ops-table">
          <thead><tr><th>工具</th><th>风险</th><th>权限</th><th>限流档</th><th>超时</th><th>状态</th><th>被谁使用</th></tr></thead>
          <tbody>
            <template v-for="tool in data?.tools ?? []" :key="tool.name">
              <tr class="ops-clickable" @click="toggle(tool.name)">
                <td><strong>{{ tool.title }}</strong> <span class="ops-muted">{{ tool.name }} · v{{ tool.version }}</span></td>
                <td>
                  <span v-for="(value, dimension) in tool.risk" :key="dimension" class="ops-chip" :class="{ 'tone-bad': value !== '只读' && value !== '不调用模型' }">{{ dimension }}：{{ value }}</span>
                </td>
                <td>{{ tool.permission }}</td>
                <td>{{ tool.rateLimitTier ?? "—" }}</td>
                <td>{{ tool.timeoutSeconds }} 秒</td>
                <td><span class="ops-status" :class="tool.enabled ? 'status-succeeded' : 'status-failed'">{{ tool.enabled ? "启用" : "已关闭" }}</span></td>
                <td>{{ tool.usedBy.join("、") || "—" }}</td>
              </tr>
              <tr v-if="expanded === tool.name">
                <td colspan="7" class="tool-detail">
                  <p><strong>给模型看的描述：</strong>{{ tool.description }}</p>
                  <p><strong>参数：</strong></p>
                  <pre>{{ JSON.stringify(tool.parameters, null, 2) }}</pre>
                </td>
              </tr>
            </template>
            <tr v-if="!data?.tools.length && !loading"><td colspan="7" class="ops-muted">还没有注册任何工具</td></tr>
          </tbody>
        </table>
      </div>
      <p class="ops-muted">点一行可以展开看描述和参数。</p>
    </div>
  </section>
</template>

<style scoped>
.tool-detail p {
  margin: 4px 0;
  white-space: normal;
}

.tool-detail pre {
  max-height: 260px;
  margin: 4px 0 0;
  padding: 10px;
  overflow: auto;
  border-radius: 8px;
  background: #1d2729;
  color: #e8efe9;
  font-size: 12px;
}
</style>
