import { ref } from "vue";

import { socialApi } from "@/api/workspace";

/**
 * Red dot on the organisation's "我的事件" (D3): how many of its events the AI or an operator
 * changed since the page was last opened. Shared between the sidebar and the page that clears it.
 */
const count = ref(0);
/**
 * Opening "我的事件" fires the sidebar refresh and "mark seen" at the same time; a refresh answer
 * that arrives after the mark would bring the old number back. Each mark bumps this, and a refresh
 * started before the bump drops its answer.
 */
let generation = 0;

async function refresh() {
  const started = generation;
  try {
    const { count: latest } = await socialApi.attention();
    if (started === generation) count.value = latest;
  } catch { /* the dot is a hint; a failed refresh just keeps the old value */ }
}

async function markSeen() {
  generation += 1;
  count.value = 0;
  try { await socialApi.markSeen(); }
  catch { /* try again next time the page opens */ }
}

export function useOrganizerAttention() {
  return { count, refresh, markSeen };
}
