<script setup lang="ts">
import BadCaseFeedback from "@/components/BadCaseFeedback.vue";
import { RouterView } from "vue-router";
</script>

<template>
  <RouterView v-slot="{ Component, route }">
    <Transition name="route" mode="out-in">
      <component :is="Component" :key="route.fullPath" />
    </Transition>
  </RouterView>
  <BadCaseFeedback />
</template>
