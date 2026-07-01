import { createPinia } from "pinia";
import { createApp } from "vue";

import App from "@/App.vue";
import { AUTH_UNAUTHORIZED_EVENT } from "@/api/client";
import router from "@/router";
import { useAuthStore } from "@/stores/auth";
import "@/styles/main.css";

const app = createApp(App);
const pinia = createPinia();

app.use(pinia);
app.use(router);

const authStore = useAuthStore(pinia);
window.addEventListener(AUTH_UNAUTHORIZED_EVENT, () => {
  authStore.clearSession();
  if (router.currentRoute.value.meta.requiresAuth) {
    void router.replace({
      name: "login",
      query: { redirect: router.currentRoute.value.fullPath }
    });
  }
});

app.mount("#app");
