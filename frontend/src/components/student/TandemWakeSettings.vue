<script setup lang="ts">
import { BellRing } from "@lucide/vue";
import { onMounted, ref } from "vue";

import { ApiError } from "@/api/client";
import { tandemApi, type WakeSettings } from "@/api/tandem";

/**
 * F4: the student's switches for proactive messages (decision 5): one for everything, one per
 * kind. All on by default; at most 3 messages a day either way.
 */
const settings = ref<WakeSettings | null>(null);
const saving = ref(false);
const error = ref("");

onMounted(async () => {
  try {
    settings.value = await tandemApi.wakeSettings();
  } catch (cause) {
    error.value = text(cause, "设置读取失败");
  }
});

async function save() {
  if (!settings.value) return;
  saving.value = true;
  error.value = "";
  try {
    settings.value = await tandemApi.saveWakeSettings(settings.value.enabled,
      settings.value.kinds.filter((kind) => !kind.enabled).map((kind) => kind.kind));
  } catch (cause) {
    error.value = text(cause, "保存失败");
  } finally {
    saving.value = false;
  }
}

function text(cause: unknown, fallback: string) {
  return cause instanceof ApiError || cause instanceof Error ? cause.message : fallback;
}
</script>

<template>
  <section v-if="settings" class="coach-review-summary wake">
    <details>
      <summary><BellRing :size="17" /><strong>主动留言</strong><span>{{ settings.enabled ? "开" : "关" }}</span></summary>
      <p class="hint">发生值得说的事时，Tandem 会在对话里主动给你留言，每天最多 3 条。</p>
      <label class="master">
        <input v-model="settings.enabled" type="checkbox" :disabled="saving" @change="save" />
        允许 Tandem 主动留言
      </label>
      <div class="kinds" :class="{ off: !settings.enabled }">
        <label v-for="kind in settings.kinds" :key="kind.kind">
          <input v-model="kind.enabled" type="checkbox" :disabled="saving || !settings.enabled" @change="save" />
          {{ kind.label }}
        </label>
      </div>
      <p v-if="error" class="module-error">{{ error }}</p>
    </details>
  </section>
</template>

<style scoped>
summary {
  display: flex;
  gap: 6px;
  align-items: center;
  cursor: pointer;
  list-style: none;
}

summary span {
  margin-left: auto;
  color: var(--muted);
  font-size: 13px;
}

.hint {
  font-size: 13px;
}

.master {
  display: flex;
  gap: 6px;
  align-items: center;
  font-weight: 600;
}

.kinds {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 4px 12px;
  margin-top: 6px;
  font-size: 13px;
}

.kinds.off {
  color: var(--muted);
}

.kinds label {
  display: flex;
  gap: 6px;
  align-items: center;
}
</style>
