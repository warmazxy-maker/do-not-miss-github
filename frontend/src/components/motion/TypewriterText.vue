<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from "vue";

const props = withDefaults(defineProps<{
  text: string;
  start?: boolean;
  typingSpeed?: number;
  showCursor?: boolean;
  cursorCharacter?: string;
}>(), {
  start: false,
  typingSpeed: 22,
  showCursor: true,
  cursorCharacter: "|"
});

const emit = defineEmits<{ complete: [] }>();

const displayedText = ref("");
const isTyping = ref(false);
const completed = ref(false);
let timer: number | undefined;

watch(
  () => [props.start, props.text] as const,
  ([start]) => {
    if (!start || completed.value) return;
    begin();
  },
  { immediate: true }
);

function begin() {
  clearTimer();
  const value = props.text ?? "";
  if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
    displayedText.value = value;
    complete();
    return;
  }

  isTyping.value = true;
  let index = 0;
  const typeNext = () => {
    displayedText.value = value.slice(0, index + 1);
    index += 1;
    if (index >= value.length) {
      complete();
      return;
    }
    timer = window.setTimeout(typeNext, props.typingSpeed);
  };

  if (!value) {
    complete();
    return;
  }
  timer = window.setTimeout(typeNext, Math.min(props.typingSpeed, 40));
}

function complete() {
  clearTimer();
  isTyping.value = false;
  if (completed.value) return;
  completed.value = true;
  nextTick(() => emit("complete"));
}

function clearTimer() {
  if (timer) window.clearTimeout(timer);
  timer = undefined;
}

onBeforeUnmount(clearTimer);
</script>

<template>
  <span class="typewriter-text">
    <span>{{ displayedText }}</span>
    <span v-if="showCursor && (isTyping || !completed)" class="typewriter-text__cursor" aria-hidden="true">
      {{ cursorCharacter }}
    </span>
  </span>
</template>

<style scoped>
.typewriter-text {
  display: inline;
  white-space: pre-wrap;
}

.typewriter-text__cursor {
  display: inline-block;
  margin-left: 0.12em;
  animation: typewriter-cursor-blink 0.72s steps(2, jump-none) infinite;
}

@keyframes typewriter-cursor-blink {
  50% { opacity: 0; }
}

@media (prefers-reduced-motion: reduce) {
  .typewriter-text__cursor { animation: none; }
}
</style>
