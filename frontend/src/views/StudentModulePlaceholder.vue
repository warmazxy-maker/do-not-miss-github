<template>
  <section class="student-module-canvas" aria-label="模块内容加载区域">
    <div class="student-placeholder-toolbar">
      <span></span>
      <span></span>
      <span></span>
    </div>
    <div class="student-placeholder-layout">
      <div class="student-placeholder-list">
        <article v-for="index in 4" :key="index">
          <span class="placeholder-block placeholder-block-small"></span>
          <span class="placeholder-block placeholder-block-title"></span>
          <span class="placeholder-block placeholder-block-text"></span>
          <span class="placeholder-block placeholder-block-text-short"></span>
        </article>
      </div>
      <aside class="student-placeholder-aside">
        <span class="placeholder-block placeholder-block-title"></span>
        <span class="placeholder-block placeholder-block-text"></span>
        <span class="placeholder-block placeholder-block-text"></span>
      </aside>
    </div>
  </section>
</template>
