const STORAGE_KEY = "do-not-miss-events";
const FOLLOW_STORAGE_KEY = "do-not-miss-followed-organizations";
const RESERVATION_STORAGE_KEY = "do-not-miss-reservations";
const COMPLETED_STORAGE_KEY = "do-not-miss-completed-records";
const CHALLENGE_STORAGE_KEY = "do-not-miss-challenges";
const SCHEDULE_STORAGE_KEY = "do-not-miss-schedule";
const AUTH_STORAGE_KEY = "do-not-miss-auth-session";
const LOCAL_USERS_STORAGE_KEY = "do-not-miss-local-users";
const PAGE_SIZE = 3;
const API_BASE_URL = "http://localhost:8080";

const categories = ["公益", "企业", "校内", "线上", "研究", "文化"];
const defaultFollowedOrganizations = ["Tokyo Bridge NPO", "Mirai Career Lab"];
const defaultLocalUsers = [
  {
    userId: "demo-student",
    username: "demo_student",
    email: "student@example.com",
    password: "demo123456",
    role: "STUDENT"
  },
  {
    userId: "demo-social",
    username: "demo_social",
    email: "social@example.com",
    password: "demo123456",
    role: "SOCIAL"
  }
];

const organizationDefaults = {
  "Tokyo Bridge NPO": {
    type: "公益组织",
    summary: "长期发布社区陪伴、儿童教育和多文化交流相关实践。"
  },
  "Mirai Career Lab": {
    type: "企业项目",
    summary: "提供展会运营、职业体验和留学生向项目协助机会。"
  },
  "Waseda Research Hub": {
    type: "大学研究室",
    summary: "关注城市生活、学生经验和社会调研项目。"
  },
  "Campus Media Studio": {
    type: "校内组织",
    summary: "发布线上内容运营、采访编辑和短视频协作任务。"
  },
  "Kansai Culture Center": {
    type: "文化机构",
    summary: "围绕地域文化节、翻译协助和跨文化活动招募学生。"
  }
};

const sampleEvents = [
  {
    id: "sample-1",
    title: "社区儿童日语阅读陪伴",
    organization: "Tokyo Bridge NPO",
    category: "公益",
    date: "2026-06-05T15:00",
    location: "东京 新宿区",
    content: "协助社区中心的儿童阅读活动，准备材料、陪伴朗读，并记录活动反馈。",
    benefitType: "skill",
    skill: "日语沟通、儿童陪伴、活动记录",
    money: "",
    createdAt: "2026-05-19T09:00:00.000Z"
  },
  {
    id: "sample-2",
    title: "留学生展会现场运营",
    organization: "Mirai Career Lab",
    category: "企业",
    date: "2026-06-12T10:30",
    location: "大阪 梅田",
    content: "负责签到、引导、问卷收集和会场整理，适合想接触活动执行的学生。",
    benefitType: "both",
    skill: "现场运营、跨文化沟通、团队协作",
    money: "8000",
    createdAt: "2026-05-19T09:10:00.000Z"
  },
  {
    id: "sample-3",
    title: "城市生活问卷访谈协助",
    organization: "Waseda Research Hub",
    category: "研究",
    date: "2026-06-18T13:00",
    location: "东京 早稻田",
    content: "协助研究室进行问卷整理、访谈预约和基础资料录入。",
    benefitType: "both",
    skill: "社会调研、访谈整理、数据录入",
    money: "3000",
    createdAt: "2026-05-19T09:20:00.000Z"
  },
  {
    id: "sample-4",
    title: "线上中文社媒内容运营",
    organization: "Campus Media Studio",
    category: "线上",
    date: "2026-06-20T18:30",
    location: "线上",
    content: "协助整理活动资讯、撰写中文推文，并进行简单数据复盘。",
    benefitType: "skill",
    skill: "内容运营、文案写作、数据复盘",
    money: "",
    createdAt: "2026-05-19T09:30:00.000Z"
  },
  {
    id: "sample-5",
    title: "关西地域文化节翻译协助",
    organization: "Kansai Culture Center",
    category: "文化",
    date: "2026-06-29T09:30",
    location: "京都 左京区",
    content: "在文化节现场协助中日翻译、游客引导和活动记录。",
    benefitType: "both",
    skill: "中日翻译、公共沟通、活动执行",
    money: "5000",
    createdAt: "2026-05-19T09:40:00.000Z"
  }
];

const defaultReservations = [
  {
    id: "reservation-sample-3",
    eventId: "sample-3",
    event: sampleEvents[2],
    reservedAt: "2026-05-19T10:00:00.000Z"
  }
];

const defaultCompletedRecords = [
  {
    id: "completed-sample-1",
    sourceType: "EVENT",
    sourceId: "sample-1",
    eventId: "sample-1",
    event: sampleEvents[0],
    reservedAt: "2026-05-10T09:00:00.000Z",
    completedAt: "2026-05-12T08:30:00.000Z",
    did: "负责阅读材料整理、现场引导和儿童朗读陪伴。",
    learned: "更敢用日语沟通，也学会了把复杂说明拆成简单步骤。"
  },
  {
    id: "completed-sample-2",
    sourceType: "EVENT",
    sourceId: "sample-2",
    eventId: "sample-2",
    event: sampleEvents[1],
    reservedAt: "2026-05-13T09:00:00.000Z",
    completedAt: "2026-05-15T09:30:00.000Z",
    did: "完成签到、问卷回收、会场动线引导，并和团队复盘现场问题。",
    learned: "理解了活动运营的节奏，现场协作和临场沟通能力明显提升。"
  },
  {
    id: "completed-challenge-sample-1",
    sourceType: "CHALLENGE",
    sourceId: "challenge-sample-1",
    eventId: "challenge-sample-1",
    event: {
      id: "challenge-sample-1",
      title: "学完黑马商城项目",
      organization: "个人挑战",
      category: "技能挑战",
      date: "2026-05-20T20:00",
      location: "自定义",
      content: "用 Java 和前端完整走一遍电商项目，重点训练项目结构、接口设计和部署流程。",
      benefitType: "skill",
      skill: "完成后端接口、前端页面和部署笔记",
      money: "",
      createdAt: "2026-05-01T09:00:00.000Z"
    },
    reservedAt: "",
    completedAt: "2026-05-20T20:00:00.000Z",
    did: "完成商品、购物车、订单三个核心模块，并整理接口文档。",
    learned: "对 Spring Boot 分层、数据库建模和前后端联调有了整体认识。"
  }
];

const defaultChallenges = [
  {
    id: "challenge-sample-1",
    title: "学完黑马商城项目",
    category: "技能挑战",
    goal: "完成后端接口、前端页面和部署笔记",
    description: "用 Java 和前端完整走一遍电商项目，重点训练项目结构、接口设计和部署流程。",
    status: "completed",
    createdAt: "2026-05-01T09:00:00.000Z",
    completedAt: "2026-05-20T20:00:00.000Z",
    did: "完成商品、购物车、订单三个核心模块，并整理接口文档。",
    learned: "对 Spring Boot 分层、数据库建模和前后端联调有了整体认识。"
  },
  {
    id: "challenge-sample-2",
    title: "英语过六级",
    category: "考试挑战",
    goal: "完成 30 天听力和阅读训练",
    description: "每天完成听力、阅读和单词复盘，目标是通过大学英语六级。",
    status: "active",
    createdAt: "2026-05-19T10:00:00.000Z",
    completedAt: "",
    did: "",
    learned: ""
  }
];

const growthDimensions = [
  {
    key: "communication",
    label: "沟通表达",
    color: "#16756f",
    words: ["日语", "沟通", "翻译", "引导", "陪伴", "交流"]
  },
  {
    key: "execution",
    label: "执行协作",
    color: "#d75d45",
    words: ["运营", "活动", "现场", "执行", "团队", "协作", "整理"]
  },
  {
    key: "research",
    label: "调研分析",
    color: "#315e86",
    words: ["研究", "调研", "访谈", "问卷", "数据", "分析", "复盘"]
  },
  {
    key: "content",
    label: "内容创作",
    color: "#c38a20",
    words: ["内容", "文案", "写作", "记录", "编辑", "推文"]
  },
  {
    key: "culture",
    label: "跨文化理解",
    color: "#6a5a91",
    words: ["跨文化", "文化", "留学生", "中文", "中日", "游客"]
  }
];

const roleButtons = document.querySelectorAll(".role-button");
const moduleButtons = document.querySelectorAll(".module-button");
const achievementTabButtons = document.querySelectorAll(".submodule-button");
const authForm = document.querySelector("#authForm");
const authUser = document.querySelector("#authUser");
const authAccount = document.querySelector("#authAccount");
const authEmail = document.querySelector("#authEmail");
const authPassword = document.querySelector("#authPassword");
const authRole = document.querySelector("#authRole");
const authSubmit = document.querySelector("#authSubmit");
const authModeToggle = document.querySelector("#authModeToggle");
const logoutButton = document.querySelector("#logoutButton");
const authMessage = document.querySelector("#authMessage");
const studentView = document.querySelector("#studentView");
const socialView = document.querySelector("#socialView");
const studentEventsModule = document.querySelector("#studentEventsModule");
const reservationsModule = document.querySelector("#reservationsModule");
const scheduleModule = document.querySelector("#scheduleModule");
const challengesModule = document.querySelector("#challengesModule");
const coachModule = document.querySelector("#coachModule");
const followingModule = document.querySelector("#followingModule");
const achievementModule = document.querySelector("#achievementModule");
const historyPanel = document.querySelector("#historyPanel");
const analysisPanel = document.querySelector("#analysisPanel");
const pageEyebrow = document.querySelector("#pageEyebrow");
const pageTitle = document.querySelector("#page-title");
const totalEvents = document.querySelector("#totalEvents");
const paidEvents = document.querySelector("#paidEvents");
const statLabels = document.querySelectorAll(".stats p");

const form = document.querySelector("#eventForm");
const challengeForm = document.querySelector("#challengeForm");
const scheduleForm = document.querySelector("#scheduleForm");
const coachForm = document.querySelector("#coachForm");
const resetFormButton = document.querySelector("#resetForm");
const eventList = document.querySelector("#eventList");
const studentEventList = document.querySelector("#studentEventList");
const studentPagination = document.querySelector("#studentPagination");
const reservationList = document.querySelector("#reservationList");
const scheduleCalendar = document.querySelector("#scheduleCalendar");
const coachMessages = document.querySelector("#coachMessages");
const coachLogList = document.querySelector("#coachLogList");
const challengeList = document.querySelector("#challengeList");
const challengePagination = document.querySelector("#challengePagination");
const organizationList = document.querySelector("#organizationList");
const historyList = document.querySelector("#historyList");
const historyPagination = document.querySelector("#historyPagination");
const eventTemplate = document.querySelector("#eventTemplate");
const studentEventTemplate = document.querySelector("#studentEventTemplate");
const reservationTemplate = document.querySelector("#reservationTemplate");
const challengeTemplate = document.querySelector("#challengeTemplate");
const historyTemplate = document.querySelector("#historyTemplate");
const organizationTemplate = document.querySelector("#organizationTemplate");
const emptyState = document.querySelector("#emptyState");
const studentEmptyState = document.querySelector("#studentEmptyState");
const reservationEmptyState = document.querySelector("#reservationEmptyState");
const scheduleEmptyState = document.querySelector("#scheduleEmptyState");
const coachLogEmptyState = document.querySelector("#coachLogEmptyState");
const challengeEmptyState = document.querySelector("#challengeEmptyState");
const organizationEmptyState = document.querySelector("#organizationEmptyState");
const achievementEmptyState = document.querySelector("#achievementEmptyState");
const historyEmptyState = document.querySelector("#historyEmptyState");
const formMessage = document.querySelector("#formMessage");
const challengeMessage = document.querySelector("#challengeMessage");
const scheduleMessage = document.querySelector("#scheduleMessage");
const coachMessage = document.querySelector("#coachMessage");
const skillField = document.querySelector("#skillField");
const moneyField = document.querySelector("#moneyField");
const filterButtons = document.querySelectorAll(".filter-button");
const categoryButtons = document.querySelectorAll(".category-button");
const benefitInputs = document.querySelectorAll("input[name='benefitType']");
const studentSearch = document.querySelector("#studentSearch");
const studentBenefitFilter = document.querySelector("#studentBenefitFilter");
const aiNeed = document.querySelector("#aiNeed");
const aiRecommend = document.querySelector("#aiRecommend");
const aiPlanRecommend = document.querySelector("#aiPlanRecommend");
const aiResult = document.querySelector("#aiResult");
const completedCount = document.querySelector("#completedCount");
const categoryCount = document.querySelector("#categoryCount");
const categoryChart = document.querySelector("#categoryChart");
const abilityMap = document.querySelector("#abilityMap");
const abilityMapDetail = document.querySelector("#abilityMapDetail");
const abilityMapRefresh = document.querySelector("#abilityMapRefresh");
const analysisGenerate = document.querySelector("#analysisGenerate");
const resumeSummary = document.querySelector("#resumeSummary");
const resumeBullets = document.querySelector("#resumeBullets");
const growthCurve = document.querySelector("#growthCurve");
const scheduleTitle = document.querySelector("#schedule-title");
const scheduleMonthLabel = document.querySelector("#scheduleMonthLabel");
const schedulePrevMonth = document.querySelector("#schedulePrevMonth");
const scheduleNextMonth = document.querySelector("#scheduleNextMonth");
const scheduleToday = document.querySelector("#scheduleToday");
const scheduleChallengeSelect = document.querySelector("#scheduleChallengeSelect");
const scheduleSubmit = document.querySelector("#scheduleSubmit");
const scheduleReset = document.querySelector("#scheduleReset");
const coachInput = document.querySelector("#coachInput");
const coachSend = document.querySelector("#coachSend");
const coachGenerateLog = document.querySelector("#coachGenerateLog");
const planModal = document.querySelector("#planModal");
const planModalClose = document.querySelector("#planModalClose");
const planImportSchedule = document.querySelector("#planImportSchedule");
const planTabs = document.querySelector("#planTabs");
const planFlow = document.querySelector("#planFlow");

const SCHEDULE_DAY_START_HOUR = 7;
const SCHEDULE_DAY_END_HOUR = 22;
const SCHEDULE_SLOT_MINUTES = 30;
const SCHEDULE_COLOR_PALETTE = [
  { bg: "rgba(126, 164, 255, 0.42)", border: "rgba(85, 121, 230, 0.46)", shadow: "rgba(85, 121, 230, 0.18)" },
  { bg: "rgba(122, 207, 190, 0.42)", border: "rgba(54, 157, 139, 0.46)", shadow: "rgba(54, 157, 139, 0.16)" },
  { bg: "rgba(244, 167, 137, 0.42)", border: "rgba(218, 117, 83, 0.42)", shadow: "rgba(218, 117, 83, 0.16)" },
  { bg: "rgba(218, 184, 255, 0.44)", border: "rgba(159, 112, 224, 0.44)", shadow: "rgba(159, 112, 224, 0.16)" },
  { bg: "rgba(238, 218, 106, 0.44)", border: "rgba(199, 162, 35, 0.42)", shadow: "rgba(199, 162, 35, 0.14)" },
  { bg: "rgba(247, 154, 190, 0.38)", border: "rgba(209, 91, 139, 0.42)", shadow: "rgba(209, 91, 139, 0.15)" },
  { bg: "rgba(151, 210, 237, 0.42)", border: "rgba(70, 154, 192, 0.44)", shadow: "rgba(70, 154, 192, 0.15)" },
  { bg: "rgba(174, 213, 129, 0.42)", border: "rgba(107, 158, 53, 0.4)", shadow: "rgba(107, 158, 53, 0.14)" }
];

let currentRole = "student";
let activeModule = "events";
let activeAchievementTab = "history";
let socialFilter = "all";
let studentCategory = "all";
let studentBenefit = "all";
let searchTerm = "";
let studentPage = 1;
let challengePage = 1;
let historyPage = 1;
let scheduleMonth = new Date();
let scheduleEditingId = "";
let scheduleDragState = null;
let authMode = "login";
let authSession = loadAuthSession();
let cachedMcpLocation = null;
let activeUserId = authSession?.user.userId ?? "guest";
if (authSession?.user.role === "SOCIAL") {
  currentRole = "social";
}
let events = [];
let backendOrganizations = [];
let followedOrganizations = [];
let reservations = [];
let completedRecords = [];
let growthTags = [];
let growthTagDetails = new Map();
let selectedGrowthTagId = "";
let loadingGrowthTagDetailId = "";
let challenges = [];
let scheduleItems = [];
let coachMessagesData = [];
let coachLogs = [];
let latestRecommendedPlans = [];
let activePlanIndex = 0;
let backendAuthToken = "";

syncBenefitFields();
syncAuthUi();
renderAll();
setDefaultScheduleTimes();
bootstrapApp();

authForm.addEventListener("submit", async (event) => {
  event.preventDefault();

  if (authMode === "login") {
    await loginLocalUser(normalize(authAccount.value), authPassword.value);
    return;
  }

  await registerLocalUser({
    username: normalize(authAccount.value),
    email: normalize(authEmail.value),
    password: authPassword.value,
    role: authRole.value
  });
});

authModeToggle.addEventListener("click", () => {
  authMode = authMode === "login" ? "register" : "login";
  syncAuthUi();
});

logoutButton.addEventListener("click", async () => {
  if (authSession?.token) {
    try {
      await fetchBackendJson("/api/auth/logout", {
        method: "POST"
      }, false);
    } catch {
      // Local logout should still complete even if the backend session already expired.
    }
  }

  authSession = null;
  backendAuthToken = "";
  activeUserId = "guest";
  localStorage.removeItem(AUTH_STORAGE_KEY);
  await reloadBackendData();
  syncAuthUi();
  renderAll();
});

roleButtons.forEach((button) => {
  button.addEventListener("click", () => {
    currentRole = button.dataset.role;
    syncRoleButtons();
    renderAll();
  });
});

moduleButtons.forEach((button) => {
  button.addEventListener("click", () => {
    activeModule = button.dataset.module;
    syncModuleButtons();
    renderAll();
  });
});

achievementTabButtons.forEach((button) => {
  button.addEventListener("click", () => {
    activeAchievementTab = button.dataset.achievementTab;
    renderAchievementTabs();
  });
});

form.addEventListener("submit", async (event) => {
  event.preventDefault();

  const formData = new FormData(form);
  const benefitType = formData.get("benefitType");
  const title = normalize(formData.get("title"));
  const organization = normalize(formData.get("organization"));
  const category = normalize(formData.get("category"));
  const date = formData.get("date");
  const endDate = formData.get("endDate");
  const location = normalize(formData.get("location"));
  const content = normalize(formData.get("content"));
  const skill = normalize(formData.get("skill"));
  const money = normalize(formData.get("money"));

  if ((benefitType === "skill" || benefitType === "both") && !skill) {
    showMessage("请填写可获得的技能或经验。", "error");
    return;
  }

  if ((benefitType === "money" || benefitType === "both") && !money) {
    showMessage("请填写金钱报酬。", "error");
    return;
  }

  if (!isValidDateRange(date, endDate)) {
    showMessage("结束时间需要晚于开始时间。", "error");
    return;
  }

  try {
    await fetchBackendJson("/api/events", {
      method: "POST",
      body: JSON.stringify({
        title,
        organizationName: organization,
        category,
        startTime: date,
        endTime: endDate,
        location,
        content,
        benefitType: mapFrontendBenefitType(benefitType),
        skill,
        moneyAmount: money ? Number(money) : null
      })
    });
    await reloadBackendData();
    renderAll();
    form.reset();
    syncBenefitFields();
    showMessage("事件已发布。", "success");
  } catch (error) {
    showMessage(`发布失败：${error.message}`, "error");
  }
});

challengeForm.addEventListener("submit", async (event) => {
  event.preventDefault();

  const formData = new FormData(challengeForm);
  try {
    await fetchBackendJson("/api/challenges", {
      method: "POST",
      body: JSON.stringify({
        title: normalize(formData.get("title")),
        category: normalize(formData.get("category")),
        goal: normalize(formData.get("goal")),
        description: normalize(formData.get("description"))
      })
    });
    await reloadBackendData();
    challengePage = 1;
    challengeForm.reset();
    showChallengeMessage("挑战已创建。", "success");
    renderAll();
  } catch (error) {
    showChallengeMessage(`创建失败：${error.message}`, "error");
  }
});

scheduleForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await saveScheduleItem();
});

scheduleReset.addEventListener("click", () => {
  resetScheduleForm();
});

scheduleChallengeSelect.addEventListener("change", syncScheduleChallengeSelection);

schedulePrevMonth.addEventListener("click", async () => {
  scheduleMonth.setDate(scheduleMonth.getDate() - 7);
  await reloadBackendData();
  renderAll();
});

scheduleNextMonth.addEventListener("click", async () => {
  scheduleMonth.setDate(scheduleMonth.getDate() + 7);
  await reloadBackendData();
  renderAll();
});

scheduleToday.addEventListener("click", async () => {
  scheduleMonth = new Date();
  await reloadBackendData();
  renderAll();
});

resetFormButton.addEventListener("click", () => {
  form.reset();
  syncBenefitFields();
  showMessage("", "success");
});

benefitInputs.forEach((input) => {
  input.addEventListener("change", syncBenefitFields);
});

filterButtons.forEach((button) => {
  button.addEventListener("click", () => {
    socialFilter = button.dataset.filter;
    filterButtons.forEach((item) => item.classList.toggle("is-active", item === button));
    renderSocialEvents();
  });
});

categoryButtons.forEach((button) => {
  button.addEventListener("click", () => {
    studentCategory = button.dataset.category;
    studentPage = 1;
    categoryButtons.forEach((item) => item.classList.toggle("is-active", item === button));
    renderStudentEvents();
  });
});

studentSearch.addEventListener("input", () => {
  searchTerm = normalize(studentSearch.value).toLowerCase();
  studentPage = 1;
  renderStudentEvents();
});

studentBenefitFilter.addEventListener("change", () => {
  studentBenefit = studentBenefitFilter.value;
  studentPage = 1;
  renderStudentEvents();
});

eventList.addEventListener("click", async (event) => {
  const deleteButton = event.target.closest(".delete-button");

  if (!deleteButton) {
    return;
  }

  const card = deleteButton.closest(".event-card");
  deleteButton.disabled = true;

  try {
    await fetchBackendJson(`/api/events/${card.dataset.eventId}`, {
      method: "DELETE"
    });
    await reloadBackendData();
    renderAll();
  } catch (error) {
    showMessage(`删除失败：${error.message}`, "error");
    deleteButton.disabled = false;
  }
});

studentEventList.addEventListener("click", handleStudentEventAction);
aiResult.addEventListener("click", handleStudentEventAction);
reservationList.addEventListener("click", handleReservationAction);
scheduleCalendar.addEventListener("click", handleScheduleAction);
scheduleCalendar.addEventListener("pointerdown", startScheduleSelection);
document.addEventListener("pointermove", updateScheduleSelection);
document.addEventListener("pointerup", finishScheduleSelection);
challengeList.addEventListener("click", handleChallengeAction);
studentPagination.addEventListener("click", (event) => {
  const action = event.target.closest("[data-page-action]")?.dataset.pageAction;

  if (!action) {
    return;
  }

  studentPage += action === "prev" ? -1 : 1;
  renderStudentEvents();
});
challengePagination.addEventListener("click", (event) => {
  const action = event.target.closest("[data-page-action]")?.dataset.pageAction;

  if (!action) {
    return;
  }

  challengePage += action === "next" ? 1 : -1;
  renderChallenges();
});
historyList.addEventListener("click", handleHistoryAction);
historyPagination.addEventListener("click", (event) => {
  const action = event.target.closest("[data-page-action]")?.dataset.pageAction;

  if (!action) {
    return;
  }

  historyPage += action === "next" ? 1 : -1;
  renderHistory();
});

abilityMap.addEventListener("click", (event) => {
  const tagButton = event.target.closest("[data-growth-tag-id]");

  if (!tagButton) {
    return;
  }

  selectGrowthTag(tagButton.dataset.growthTagId);
});

abilityMapDetail.addEventListener("click", async (event) => {
  const milestoneButton = event.target.closest("[data-milestone-evidence-id]");

  if (!milestoneButton) {
    return;
  }

  await toggleGrowthMilestone(milestoneButton.dataset.milestoneEvidenceId);
});

abilityMapRefresh.addEventListener("click", async () => {
  if (!authSession?.token) {
    showAuthMessage("请先登录后再刷新能力地图。", "error");
    return;
  }

  const originalText = abilityMapRefresh.textContent;
  abilityMapRefresh.disabled = true;
  abilityMapRefresh.textContent = "刷新中...";

  try {
    await fetchBackendJson("/api/achievements/growth-tags/rebuild", {
      method: "POST"
    });
    growthTagDetails = new Map();
    growthTags = (await fetchBackendJson("/api/achievements/growth-tags")).map(mapBackendGrowthTag);
    selectedGrowthTagId = growthTags[0]?.id ?? "";
    renderAbilityMap();
    showAuthMessage("能力地图已刷新。", "success");
  } catch (error) {
    showAuthMessage(`刷新失败：${error.message}`, "error");
  } finally {
    abilityMapRefresh.disabled = false;
    abilityMapRefresh.textContent = originalText;
  }
});

organizationList.addEventListener("click", async (event) => {
  const button = event.target.closest(".secondary-button");

  if (!button) {
    return;
  }

  await toggleOrganization(button.dataset.organization);
});

aiRecommend.addEventListener("click", () => {
  renderAiRecommendations(true);
});

aiPlanRecommend.addEventListener("click", () => {
  renderAiPlans();
});

planModalClose.addEventListener("click", closePlanModal);
planImportSchedule.addEventListener("click", importActivePlanToSchedule);
planModal.addEventListener("click", (event) => {
  if (event.target.closest("[data-plan-close]")) {
    closePlanModal();
  }
});
planTabs.addEventListener("click", (event) => {
  const index = Number(event.target.closest("[data-plan-index]")?.dataset.planIndex);
  if (Number.isInteger(index)) {
    openPlanModal(index);
  }
});

analysisGenerate.addEventListener("click", () => {
  renderSelfAnalysis(true);
});

coachForm.addEventListener("submit", async (event) => {
  event.preventDefault();
  await sendCoachMessage();
});

coachGenerateLog.addEventListener("click", async () => {
  await generateCoachLog();
});

function renderAll() {
  renderRoleView();
  renderSocialEvents();
  renderStudentEvents();
  renderReservations();
  renderSchedule();
  renderChallenges();
  renderCoach();
  renderOrganizations();
  renderAchievements();
  renderStats();
  renderAiRecommendations(false);
}

function syncAuthUi() {
  const loggedIn = Boolean(authSession);
  authUser.textContent = loggedIn
    ? `已登录：${authSession.user.username}（${authSession.user.role === "SOCIAL" ? "社会端" : "学生"}）`
    : "未登录：当前为游客数据";
  authEmail.hidden = authMode === "login";
  authRole.hidden = authMode === "login";
  logoutButton.hidden = !loggedIn;
  authSubmit.textContent = authMode === "login" ? "登录" : "注册";
  authModeToggle.textContent = authMode === "login" ? "注册" : "去登录";
  authAccount.placeholder = authMode === "login" ? "用户名 / 邮箱" : "用户名";
  authEmail.required = authMode === "register";
  authMessage.textContent = "";
}

async function loginLocalUser(account, password) {
  try {
    const response = await fetchPublicJson("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ account, password })
    });
    await setAuthSession(response);
    showAuthMessage("登录成功。", "success");
  } catch (error) {
    showAuthMessage(`登录失败：${error.message}`, "error");
  }
}

async function registerLocalUser(userInput) {
  if (userInput.password.length < 6) {
    showAuthMessage("密码至少 6 位。", "error");
    return;
  }

  try {
    const response = await fetchPublicJson("/api/auth/register", {
      method: "POST",
      body: JSON.stringify(userInput)
    });
    await setAuthSession(response);
    showAuthMessage("注册成功。", "success");
  } catch (error) {
    showAuthMessage(`注册失败：${error.message}`, "error");
  }
}

async function setAuthSession(authResponse) {
  authSession = authResponse;
  backendAuthToken = authResponse.token;
  activeUserId = authSession.user.userId;
  currentRole = authSession.user.role === "SOCIAL" ? "social" : "student";
  localStorage.setItem(AUTH_STORAGE_KEY, JSON.stringify(authSession));
  await reloadBackendData();
  syncAuthUi();
  renderAll();
}

async function reloadUserScopedData() {
  await reloadBackendData();
  challengePage = 1;
  historyPage = 1;
}

function renderRoleView() {
  const isStudent = currentRole === "student";

  studentView.hidden = !isStudent;
  socialView.hidden = isStudent;
  studentEventsModule.hidden = activeModule !== "events";
  reservationsModule.hidden = activeModule !== "reservations";
  scheduleModule.hidden = activeModule !== "schedule";
  challengesModule.hidden = activeModule !== "challenges";
  coachModule.hidden = activeModule !== "coach";
  followingModule.hidden = activeModule !== "following";
  achievementModule.hidden = activeModule !== "achievement";

  pageEyebrow.textContent = isStudent ? "学生端" : "社会端";
  pageTitle.textContent = isStudent
    ? "不错过真正能带来成长的实践机会。"
    : "把值得参与的机会发布给学生。";

  syncRoleButtons();
  syncModuleButtons();
  renderAchievementTabs();
}

function renderStats() {
  const isStudent = currentRole === "student";

  if (!isStudent) {
    totalEvents.textContent = String(events.length);
    paidEvents.textContent = String(events.filter(hasMoney).length);
    statLabels[0].textContent = "已发布";
    statLabels[1].textContent = "含报酬";
    return;
  }

  if (activeModule === "reservations") {
    totalEvents.textContent = String(reservations.length);
    paidEvents.textContent = String(completedRecords.length);
    statLabels[0].textContent = "已预约";
    statLabels[1].textContent = "已完成";
    return;
  }

  if (activeModule === "schedule") {
    totalEvents.textContent = String(scheduleItems.length);
    paidEvents.textContent = String(scheduleItems.filter((item) => item.itemType === "CHALLENGE").length);
    statLabels[0].textContent = "本月日程";
    statLabels[1].textContent = "挑战安排";
    return;
  }

  if (activeModule === "achievement") {
    totalEvents.textContent = String(completedRecords.length);
    paidEvents.textContent = String(getCategoryCounts().length);
    statLabels[0].textContent = "已完成";
    statLabels[1].textContent = "参与类别";
    return;
  }

  if (activeModule === "challenges") {
    totalEvents.textContent = String(challenges.filter((item) => item.status === "active").length);
    paidEvents.textContent = String(challenges.filter((item) => item.status === "completed").length);
    statLabels[0].textContent = "进行中";
    statLabels[1].textContent = "已完成";
    return;
  }

  if (activeModule === "coach") {
    totalEvents.textContent = String(coachMessagesData.length);
    paidEvents.textContent = String(coachLogs.length);
    statLabels[0].textContent = "今日对话";
    statLabels[1].textContent = "成长日志";
    return;
  }

  totalEvents.textContent = String(events.length);
  paidEvents.textContent = String(events.filter(hasMoney).length);
  statLabels[0].textContent = activeModule === "following" ? "关注组织" : "可参与事件";
  statLabels[1].textContent = activeModule === "following" ? "已完成" : "含报酬";

  if (activeModule === "following") {
    totalEvents.textContent = String(followedOrganizations.length);
    paidEvents.textContent = String(completedRecords.length);
  }
}

function renderSocialEvents() {
  const visibleEvents = getSocialEvents();
  eventList.innerHTML = "";

  visibleEvents.forEach((item) => {
    const card = eventTemplate.content.firstElementChild.cloneNode(true);
    fillEventCard(card, item);
    eventList.append(card);
  });

  const hasVisibleEvents = visibleEvents.length > 0;
  emptyState.classList.toggle("is-visible", !hasVisibleEvents);
  eventList.hidden = !hasVisibleEvents;
}

function renderStudentEvents() {
  const visibleEvents = getStudentEvents();
  studentPage = clampPage(studentPage, visibleEvents.length);
  const pageEvents = getPageItems(visibleEvents, studentPage);
  studentEventList.innerHTML = "";

  pageEvents.forEach((item) => {
    studentEventList.append(createStudentEventCard(item));
  });

  const hasVisibleEvents = visibleEvents.length > 0;
  studentEmptyState.classList.toggle("is-visible", !hasVisibleEvents);
  studentEventList.hidden = !hasVisibleEvents;
  renderPagination(studentPagination, visibleEvents.length, studentPage);
}

function renderReservations() {
  reservationList.innerHTML = "";

  reservations.forEach((reservation) => {
    const item = getReservationEvent(reservation);

    if (!item) {
      return;
    }

    const card = reservationTemplate.content.firstElementChild.cloneNode(true);
    card.dataset.reservationId = reservation.id;
    fillEventCard(card, item);
    reservationList.append(card);
  });

  const hasReservations = reservations.length > 0;
  reservationEmptyState.classList.toggle("is-visible", !hasReservations);
  reservationList.hidden = !hasReservations;
}

function renderSchedule() {
  renderScheduleChallengeOptions();
  scheduleMonthLabel.textContent = formatMonthLabel(scheduleMonth);
  scheduleCalendar.innerHTML = "";

  const days = buildCalendarDays(scheduleMonth);
  const itemsByDate = groupScheduleItemsByDate(scheduleItems);
  const todayKey = toDateKey(new Date());
  const monthKey = toMonthKey(scheduleMonth);

  days.forEach((day) => {
    const cell = document.createElement("div");
    const dateHeader = document.createElement("div");
    const dayNumber = document.createElement("span");
    const countLabel = document.createElement("span");
    const dateKey = toDateKey(day);
    const dayItems = itemsByDate.get(dateKey) ?? [];

    cell.className = "schedule-day";
    cell.classList.toggle("is-outside", toMonthKey(day) !== monthKey);
    cell.classList.toggle("is-today", dateKey === todayKey);
    cell.dataset.date = dateKey;
    dateHeader.className = "schedule-date";
    dayNumber.textContent = String(day.getDate());
    countLabel.textContent = dayItems.length > 0 ? `${dayItems.length}项` : "";
    dateHeader.append(dayNumber, countLabel);
    cell.append(dateHeader);

    dayItems.forEach((item) => {
      cell.append(createScheduleItemElement(item));
    });

    scheduleCalendar.append(cell);
  });

  const hasSchedule = scheduleItems.length > 0;
  scheduleEmptyState.classList.toggle("is-visible", !hasSchedule);
}

function createScheduleItemElement(item) {
  const wrapper = document.createElement("div");
  const title = document.createElement("div");
  const time = document.createElement("div");
  const actions = document.createElement("div");
  const editButton = document.createElement("button");
  const deleteButton = document.createElement("button");

  wrapper.className = "schedule-item";
  wrapper.dataset.scheduleId = item.id;
  wrapper.dataset.type = item.itemType;
  title.className = "schedule-item-title";
  title.textContent = item.title;
  time.className = "schedule-item-time";
  time.textContent = formatTimeRange(item.startTime, item.endTime);
  actions.className = "schedule-item-actions";
  editButton.className = "schedule-mini-button edit-schedule-button";
  editButton.type = "button";
  editButton.textContent = "编辑";
  deleteButton.className = "schedule-mini-button delete-schedule-button";
  deleteButton.type = "button";
  deleteButton.textContent = "删除";

  actions.append(editButton, deleteButton);
  wrapper.append(title, time, actions);
  return wrapper;
}

function renderSchedule() {
  renderScheduleChallengeOptions();
  const weekDays = buildWeekDays(scheduleMonth);
  const visibleItems = getScheduleItemsForWeek(weekDays);
  scheduleTitle.textContent = "周视图";
  scheduleMonthLabel.textContent = formatWeekLabel(weekDays);
  scheduleCalendar.innerHTML = "";

  const itemsByDate = groupScheduleItemsByDate(scheduleItems);
  const todayKey = toDateKey(new Date());
  const weekView = document.createElement("div");
  const header = document.createElement("div");
  const timeline = document.createElement("div");
  const timeColumn = document.createElement("div");

  weekView.className = "schedule-week-view";
  header.className = "schedule-week-header";
  timeline.className = "schedule-week-timeline";
  timeColumn.className = "schedule-time-column";

  header.append(createScheduleCorner());
  weekDays.forEach((day) => {
    const dateKey = toDateKey(day);
    const dayHeader = document.createElement("div");
    const weekday = document.createElement("span");
    const date = document.createElement("strong");
    const count = document.createElement("small");
    const dayItems = itemsByDate.get(dateKey) ?? [];

    dayHeader.className = "schedule-week-day-header";
    dayHeader.classList.toggle("is-today", dateKey === todayKey);
    weekday.textContent = formatWeekday(day);
    date.textContent = String(day.getDate());
    count.textContent = dayItems.length > 0 ? `${dayItems.length} 项` : "";
    dayHeader.append(weekday, date, count);
    header.append(dayHeader);
  });

  buildScheduleHours().forEach((hour) => {
    const label = document.createElement("div");
    label.className = "schedule-hour-label";
    label.textContent = `${String(hour).padStart(2, "0")}:00`;
    timeColumn.append(label);
  });
  timeline.append(timeColumn);

  weekDays.forEach((day) => {
    const dateKey = toDateKey(day);
    const column = document.createElement("div");
    const dayItems = itemsByDate.get(dateKey) ?? [];

    column.className = "schedule-day-column";
    column.classList.toggle("is-today", dateKey === todayKey);
    column.dataset.date = dateKey;

    buildScheduleSlots().forEach((slotIndex) => {
      const slot = document.createElement("button");
      slot.className = "schedule-time-slot";
      slot.type = "button";
      slot.dataset.date = dateKey;
      slot.dataset.slotIndex = String(slotIndex);
      slot.setAttribute("aria-label", `${dateKey} ${slotTimeLabel(slotIndex)}`);
      column.append(slot);
    });

    dayItems.forEach((item) => {
      column.append(createScheduleItemElement(item));
    });

    timeline.append(column);
  });

  weekView.append(header, timeline);
  scheduleCalendar.append(weekView);
  scheduleEmptyState.classList.toggle("is-visible", visibleItems.length === 0);
}

function createScheduleItemElement(item) {
  const wrapper = document.createElement("div");
  const title = document.createElement("div");
  const time = document.createElement("div");
  const actions = document.createElement("div");
  const editButton = document.createElement("button");
  const deleteButton = document.createElement("button");

  wrapper.className = "schedule-item";
  wrapper.dataset.scheduleId = item.id;
  wrapper.dataset.type = item.itemType;
  applyScheduleItemPosition(wrapper, item);
  applyScheduleItemColor(wrapper, item);
  title.className = "schedule-item-title";
  title.textContent = item.title;
  time.className = "schedule-item-time";
  time.textContent = formatTimeRange(item.startTime, item.endTime);
  actions.className = "schedule-item-actions";
  editButton.className = "schedule-mini-button edit-schedule-button";
  editButton.type = "button";
  editButton.textContent = "编辑";
  deleteButton.className = "schedule-mini-button delete-schedule-button";
  deleteButton.type = "button";
  deleteButton.textContent = "删除";

  actions.append(editButton, deleteButton);
  wrapper.append(title, time, actions);
  return wrapper;
}

function createScheduleCorner() {
  const corner = document.createElement("div");
  corner.className = "schedule-week-corner";
  corner.textContent = "时间";
  return corner;
}

function buildWeekDays(anchorDate) {
  const start = startOfWeek(anchorDate);
  return Array.from({ length: 7 }, (_, index) => {
    const day = new Date(start);
    day.setDate(start.getDate() + index);
    return day;
  });
}

function startOfWeek(value) {
  const date = new Date(value);
  const mondayOffset = (date.getDay() + 6) % 7;
  date.setHours(0, 0, 0, 0);
  date.setDate(date.getDate() - mondayOffset);
  return date;
}

function getScheduleItemsForWeek(weekDays) {
  const keys = new Set(weekDays.map(toDateKey));
  return scheduleItems.filter((item) => keys.has(toDateKey(new Date(item.startTime))));
}

function buildScheduleHours() {
  return Array.from(
    { length: SCHEDULE_DAY_END_HOUR - SCHEDULE_DAY_START_HOUR },
    (_, index) => SCHEDULE_DAY_START_HOUR + index
  );
}

function buildScheduleSlots() {
  const slotCount = ((SCHEDULE_DAY_END_HOUR - SCHEDULE_DAY_START_HOUR) * 60) / SCHEDULE_SLOT_MINUTES;
  return Array.from({ length: slotCount }, (_, index) => index);
}

function slotTimeLabel(slotIndex) {
  const totalMinutes = SCHEDULE_DAY_START_HOUR * 60 + slotIndex * SCHEDULE_SLOT_MINUTES;
  const hour = Math.floor(totalMinutes / 60);
  const minute = totalMinutes % 60;
  return `${String(hour).padStart(2, "0")}:${String(minute).padStart(2, "0")}`;
}

function applyScheduleItemPosition(element, item) {
  const start = new Date(item.startTime);
  const end = new Date(item.endTime);
  const dayStart = new Date(start);
  dayStart.setHours(SCHEDULE_DAY_START_HOUR, 0, 0, 0);
  const dayEnd = new Date(start);
  dayEnd.setHours(SCHEDULE_DAY_END_HOUR, 0, 0, 0);
  const clampedStart = Math.max(start.getTime(), dayStart.getTime());
  const clampedEnd = Math.min(end.getTime(), dayEnd.getTime());
  const topMinutes = Math.max((clampedStart - dayStart.getTime()) / 60000, 0);
  const durationMinutes = Math.max((clampedEnd - clampedStart) / 60000, SCHEDULE_SLOT_MINUTES);
  const dayMinutes = (SCHEDULE_DAY_END_HOUR - SCHEDULE_DAY_START_HOUR) * 60;

  element.style.setProperty("--schedule-top", `${(topMinutes / dayMinutes) * 100}%`);
  element.style.setProperty("--schedule-height", `${(durationMinutes / dayMinutes) * 100}%`);
}

function applyScheduleItemColor(element, item) {
  const key = `${item.itemType ?? "CUSTOM"}-${item.id ?? ""}-${item.title ?? ""}`;
  const color = SCHEDULE_COLOR_PALETTE[scheduleColorIndex(key)];
  element.style.setProperty("--schedule-bg", color.bg);
  element.style.setProperty("--schedule-border", color.border);
  element.style.setProperty("--schedule-shadow", color.shadow);
}

function scheduleColorIndex(value) {
  let hash = 0;
  for (let index = 0; index < value.length; index += 1) {
    hash = (hash * 31 + value.charCodeAt(index)) | 0;
  }
  return Math.abs(hash) % SCHEDULE_COLOR_PALETTE.length;
}

function formatWeekLabel(weekDays) {
  const first = weekDays[0];
  const last = weekDays[weekDays.length - 1];
  const startText = new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric"
  }).format(first);
  const endText = new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric"
  }).format(last);
  return `${first.getFullYear()} ${startText} - ${endText}`;
}

function formatWeekday(value) {
  return new Intl.DateTimeFormat("zh-CN", {
    weekday: "short"
  }).format(value);
}

function renderChallenges() {
  const activeChallenges = challenges.filter((item) => item.status === "active");
  challengeList.innerHTML = "";
  challengePage = clampPage(challengePage, activeChallenges.length);

  getPageItems(activeChallenges, challengePage).forEach((challenge) => {
    const card = challengeTemplate.content.firstElementChild.cloneNode(true);
    card.dataset.challengeId = challenge.id;
    card.querySelector(".event-date").textContent = `创建于 ${formatDate(challenge.createdAt)}`;
    card.querySelector(".event-title").textContent = challenge.title;
    card.querySelector(".event-meta").textContent = `${challenge.category} · ${challenge.goal}`;
    card.querySelector(".event-content").textContent = challenge.description;
    challengeList.append(card);
  });

  const hasChallenges = activeChallenges.length > 0;
  challengeEmptyState.classList.toggle("is-visible", !hasChallenges);
  challengeList.hidden = !hasChallenges;
  renderPagination(challengePagination, activeChallenges.length, challengePage);
}

function renderCoach() {
  coachMessages.innerHTML = "";
  coachMessagesData.forEach((message) => {
    coachMessages.append(createCoachMessageElement(message));
  });

  if (coachMessagesData.length === 0) {
    const note = createAiNote("和教练说说今天做了什么，或者直接输入“生成日志”。");
    coachMessages.append(note);
  }

  coachLogList.innerHTML = "";
  coachLogs.forEach((log) => {
    coachLogList.append(createCoachLogElement(log));
  });

  const hasLogs = coachLogs.length > 0;
  coachLogEmptyState.classList.toggle("is-visible", !hasLogs);
  coachLogList.hidden = !hasLogs;
}

function createCoachMessageElement(message) {
  const wrapper = document.createElement("article");
  const role = document.createElement("p");
  const content = document.createElement("p");

  wrapper.className = "coach-bubble";
  wrapper.classList.toggle("is-user", message.role === "USER");
  role.className = "coach-bubble-role";
  role.textContent = message.role === "USER" ? "我" : "教练";
  content.className = "coach-bubble-content";
  content.textContent = message.content;
  wrapper.append(role, content);
  return wrapper;
}

function createCoachLogElement(log) {
  const card = document.createElement("article");
  const date = document.createElement("p");
  const title = document.createElement("h3");
  const summary = document.createElement("p");
  const content = document.createElement("p");
  const tags = document.createElement("div");

  card.className = "coach-log-card";
  date.className = "coach-log-date";
  title.className = "coach-log-title";
  summary.className = "coach-log-summary";
  content.className = "coach-log-content";
  tags.className = "coach-log-tags";
  date.textContent = log.logDate;
  title.textContent = log.title;
  summary.textContent = log.summary;
  content.textContent = log.content;
  tags.append(...(log.tags ?? []).map((tag) => createTagChip(tag)));
  card.append(date, title, summary, content, tags);
  return card;
}

function renderOrganizations() {
  const profiles = followedOrganizations.map(buildOrganizationProfile).filter(Boolean);
  organizationList.innerHTML = "";

  profiles.forEach((profile) => {
    const card = organizationTemplate.content.firstElementChild.cloneNode(true);
    card.querySelector(".organization-type").textContent = profile.type;
    card.querySelector(".organization-name").textContent = profile.name;
    card.querySelector(".organization-summary").textContent = profile.summary;
    card.querySelector(".secondary-button").dataset.organization = profile.name;
    card.querySelector(".organization-tags").append(...profile.tags.map((tag) => createTagChip(tag)));
    organizationList.append(card);
  });

  const hasOrganizations = profiles.length > 0;
  organizationEmptyState.classList.toggle("is-visible", !hasOrganizations);
  organizationList.hidden = !hasOrganizations;
}

function renderAchievements() {
  renderAbilityMap();
  renderCategoryChart();
  renderHistory();
  renderSelfAnalysis();
}

function renderAbilityMap() {
  const orderedTags = [...growthTags].sort((a, b) =>
    b.importanceScore - a.importanceScore ||
    b.score - a.score ||
    b.evidenceCount - a.evidenceCount ||
    a.name.localeCompare(b.name)
  );
  abilityMap.innerHTML = "";
  abilityMapDetail.innerHTML = "";
  abilityMapRefresh.hidden = !authSession?.token || completedRecords.length === 0;

  if (!selectedGrowthTagId || !orderedTags.some((tag) => tag.id === selectedGrowthTagId)) {
    selectedGrowthTagId = orderedTags[0]?.id ?? "";
  }

  if (orderedTags.length === 0) {
    const note = document.createElement("div");
    note.className = "ability-map-empty";
    note.textContent = completedRecords.length > 0
      ? "能力标签待生成。"
      : "完成活动或挑战后生成能力标签。";
    abilityMap.append(note);
    abilityMapDetail.hidden = true;
    return;
  }

  const visibleTags = orderedTags.slice(0, 14);
  if (!visibleTags.some((tag) => tag.id === selectedGrowthTagId)) {
    selectedGrowthTagId = visibleTags[0]?.id ?? "";
  }

  const maxScore = Math.max(...orderedTags.map((tag) => tag.score), 1);
  const stage = document.createElement("div");
  const avatar = document.createElement("div");
  const avatarInitial = document.createElement("strong");
  const avatarLabel = document.createElement("span");
  const tagCloud = document.createElement("div");

  stage.className = "ability-map-stage";
  avatar.className = "ability-avatar";
  avatarInitial.textContent = (authSession?.user.username ?? "Me").slice(0, 1).toUpperCase();
  avatarLabel.textContent = `${orderedTags.length} 个能力标签`;
  avatar.append(avatarInitial, avatarLabel);
  tagCloud.className = "ability-tag-cloud";

  visibleTags.forEach((tag, index) => {
    const button = document.createElement("button");
    const name = document.createElement("span");
    const meta = document.createElement("small");
    const intensity = tag.score / maxScore;
    const level = Math.max(0, Math.min(4, Math.floor(intensity * 5)));
    const position = getAbilityBubblePosition(index, visibleTags.length, intensity);

    button.type = "button";
    button.className = `ability-tag ability-tag-level-${level}`;
    button.dataset.growthTagId = tag.id;
    button.classList.toggle("is-active", tag.id === selectedGrowthTagId);
    button.classList.toggle("is-milestone", tag.importanceScore > 0);
    button.style.setProperty("--bubble-x", `${position.x}%`);
    button.style.setProperty("--bubble-y", `${position.y}%`);
    button.style.setProperty("--bubble-size", `${Math.round(92 + intensity * 48)}px`);
    button.style.setProperty("--bubble-delay", `${Math.min(index * 45, 420)}ms`);
    name.textContent = tag.name;
    meta.textContent = `${tag.score} 分 · ${tag.evidenceCount} 条证据`;
    button.append(name, meta);
    tagCloud.append(button);
  });

  stage.append(avatar, tagCloud);
  abilityMap.append(stage);
  const selectedTag = orderedTags.find((tag) => tag.id === selectedGrowthTagId) ?? orderedTags[0];
  renderAbilityMapDetail(selectedTag);
  queueGrowthTagDetailLoad(selectedTag.id);
}

function getAbilityBubblePosition(index, total, intensity) {
  if (total === 1) {
    return { x: 73, y: 48 };
  }

  const angle = -90 + (360 / total) * index;
  const radians = (angle * Math.PI) / 180;
  const ringOffset = index % 2 === 0 ? 0 : -6;
  const radiusX = 35 + ringOffset + intensity * 2;
  const radiusY = 31 + ringOffset + intensity * 2;

  return {
    x: 50 + Math.cos(radians) * radiusX,
    y: 50 + Math.sin(radians) * radiusY
  };
}

function renderAbilityMapDetail(tag) {
  abilityMapDetail.innerHTML = "";

  if (!tag) {
    abilityMapDetail.hidden = true;
    return;
  }

  abilityMapDetail.hidden = false;
  const detail = growthTagDetails.get(tag.id);
  const title = document.createElement("strong");
  const description = document.createElement("p");
  const metrics = document.createElement("div");
  const score = document.createElement("span");
  const evidence = document.createElement("span");
  const milestone = document.createElement("span");
  const timelineTitle = document.createElement("div");

  title.textContent = tag.name;
  description.textContent = tag.description || "来自已完成活动、挑战和复盘的成长证据。";
  metrics.className = "ability-map-metrics";
  score.textContent = `累计 ${tag.score} 分`;
  evidence.textContent = `${tag.evidenceCount} 条证据`;
  milestone.textContent = tag.importanceScore > 0 ? `重要性 ${tag.importanceScore}` : "暂无里程碑";
  timelineTitle.className = "milestone-road-title";
  timelineTitle.textContent = "成长里程碑";

  metrics.append(score, evidence, milestone);
  abilityMapDetail.append(title, description, metrics, timelineTitle);

  if (!detail) {
    const loading = document.createElement("div");
    loading.className = "milestone-road-note";
    loading.textContent = loadingGrowthTagDetailId === tag.id ? "正在读取标签证据..." : "点击标签查看成长证据。";
    abilityMapDetail.append(loading);
    return;
  }

  renderMilestoneRoad(detail.evidences ?? []);
}

function renderMilestoneRoad(evidences) {
  if (evidences.length === 0) {
    const empty = document.createElement("div");
    empty.className = "milestone-road-note";
    empty.textContent = "这个标签暂时还没有可展示的证据。";
    abilityMapDetail.append(empty);
    return;
  }

  const road = document.createElement("div");
  road.className = "milestone-road";

  [...evidences]
    .sort((a, b) => compareDate(a.occurredAt, b.occurredAt))
    .forEach((evidence, index) => {
      const node = document.createElement("article");
      const marker = document.createElement("div");
      const body = document.createElement("div");
      const topLine = document.createElement("div");
      const date = document.createElement("span");
      const score = document.createElement("span");
      const title = document.createElement("h4");
      const summary = document.createElement("p");
      const reflection = document.createElement("div");
      const action = document.createElement("button");

      node.className = "milestone-node";
      node.classList.toggle("is-important", evidence.milestone);
      marker.className = "milestone-marker";
      marker.textContent = evidence.milestone ? "★" : String(index + 1);
      body.className = "milestone-body";
      topLine.className = "milestone-topline";
      date.textContent = formatDate(evidence.occurredAt);
      score.textContent = `+${evidence.scoreDelta} 分`;
      title.textContent = evidence.title;
      summary.textContent = evidence.summary || "这条经历沉淀为当前能力标签的证据。";
      reflection.className = "milestone-reflection";
      action.className = "secondary-button milestone-action";
      action.type = "button";
      action.dataset.milestoneEvidenceId = evidence.id;
      action.textContent = evidence.milestone ? "取消里程碑" : "标记里程碑";

      if (evidence.did || evidence.learned || evidence.milestoneReason) {
        const fragments = [];
        if (evidence.did) {
          fragments.push(`做了：${evidence.did}`);
        }
        if (evidence.learned) {
          fragments.push(`学到：${evidence.learned}`);
        }
        if (evidence.milestoneReason) {
          fragments.push(`重要原因：${evidence.milestoneReason}`);
        }
        reflection.textContent = fragments.join(" / ");
      } else {
        reflection.textContent = "还没有补充复盘。";
      }

      topLine.append(date, score);
      body.append(topLine, title, summary, reflection, action);
      node.append(marker, body);
      road.append(node);
    });

  abilityMapDetail.append(road);
}

function selectGrowthTag(tagId) {
  selectedGrowthTagId = tagId;
  renderAbilityMap();
}

async function queueGrowthTagDetailLoad(tagId) {
  if (!authSession?.token || !tagId || growthTagDetails.has(tagId) || loadingGrowthTagDetailId === tagId) {
    return;
  }

  loadingGrowthTagDetailId = tagId;
  renderAbilityMapDetail(growthTags.find((tag) => tag.id === tagId));

  try {
    const detail = mapBackendGrowthTagDetail(await fetchBackendJson(`/api/achievements/growth-tags/${tagId}`));
    growthTagDetails.set(tagId, detail);
  } catch (error) {
    showAuthMessage(`标签详情读取失败：${error.message}`, "error");
  } finally {
    if (loadingGrowthTagDetailId === tagId) {
      loadingGrowthTagDetailId = "";
    }
    if (selectedGrowthTagId === tagId) {
      renderAbilityMapDetail(growthTags.find((tag) => tag.id === tagId));
    }
  }
}

async function toggleGrowthMilestone(evidenceId) {
  const detail = growthTagDetails.get(selectedGrowthTagId);
  const evidence = detail?.evidences.find((item) => item.id === evidenceId);
  const tag = growthTags.find((item) => item.id === selectedGrowthTagId);

  if (!evidence || !tag) {
    return;
  }

  const nextMilestone = !evidence.milestone;
  const reason = nextMilestone
    ? evidence.milestoneReason || `${evidence.title} 是「${tag.name}」能力的重要成长节点。`
    : "";

  try {
    await fetchBackendJson(`/api/achievements/growth-tags/evidences/${evidenceId}/milestone`, {
      method: "PUT",
      body: JSON.stringify({
        milestone: nextMilestone,
        reason
      })
    });
    const [tagData, detailData] = await Promise.all([
      fetchBackendJson("/api/achievements/growth-tags"),
      fetchBackendJson(`/api/achievements/growth-tags/${selectedGrowthTagId}`)
    ]);
    growthTags = tagData.map(mapBackendGrowthTag);
    growthTagDetails.set(selectedGrowthTagId, mapBackendGrowthTagDetail(detailData));
    renderAbilityMap();
  } catch (error) {
    showAuthMessage(`里程碑更新失败：${error.message}`, "error");
  }
}

function renderCategoryChart() {
  const counts = getCategoryCounts();
  const maxCount = Math.max(...counts.map((item) => item.count), 1);
  categoryChart.innerHTML = "";
  completedCount.textContent = String(completedRecords.length);
  categoryCount.textContent = String(counts.length);

  counts.forEach((item, index) => {
    const row = document.createElement("div");
    const label = document.createElement("span");
    const track = document.createElement("div");
    const bar = document.createElement("div");
    const value = document.createElement("strong");

    row.className = "chart-row";
    track.className = "chart-track";
    bar.className = "chart-bar";
    label.textContent = item.category;
    value.textContent = String(item.count);
    bar.style.width = `${(item.count / maxCount) * 100}%`;
    bar.style.background = getCategoryColor(index);

    track.append(bar);
    row.append(label, track, value);
    categoryChart.append(row);
  });

  const hasCompletedRecords = completedRecords.length > 0;
  achievementEmptyState.classList.toggle("is-visible", !hasCompletedRecords);
  categoryChart.hidden = !hasCompletedRecords;
}

function renderHistory() {
  const orderedRecords = getOrderedCompletedRecords();
  historyList.innerHTML = "";
  historyPage = clampPage(historyPage, orderedRecords.length);

  getPageItems(orderedRecords, historyPage).forEach((record) => {
    const item = getRecordEvent(record);
    const card = historyTemplate.content.firstElementChild.cloneNode(true);
    card.dataset.recordId = record.id;
    card.querySelector(".event-date").textContent = `完成于 ${formatDate(record.completedAt)}`;
    card.querySelector(".event-title").textContent = item.title;
    card.querySelector(".event-meta").textContent = `${item.organization} · ${item.category}`;
    card.querySelector(".did-input").value = record.did ?? "";
    card.querySelector(".learned-input").value = record.learned ?? "";
    historyList.append(card);
  });

  const hasHistory = orderedRecords.length > 0;
  historyEmptyState.classList.toggle("is-visible", !hasHistory);
  historyList.hidden = !hasHistory;
  renderPagination(historyPagination, orderedRecords.length, historyPage);
}

function renderAchievementTabs() {
  achievementTabButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.achievementTab === activeAchievementTab);
  });

  historyPanel.hidden = activeAchievementTab !== "history";
  analysisPanel.hidden = activeAchievementTab !== "analysis";
}

async function renderSelfAnalysis(useBackend = false) {
  const localAnalysis = buildSelfAnalysis();

  if (!useBackend) {
    renderAnalysisContent(localAnalysis.summary, localAnalysis.bullets);
    renderGrowthCurve(localAnalysis.progress);
    return;
  }

  const originalText = analysisGenerate.textContent;
  analysisGenerate.disabled = true;
  analysisGenerate.textContent = "生成中...";
  renderAnalysisContent("AI 正在读取后端成就记录并生成分析。", ["正在连接千问模型，请稍等。"]);
  renderGrowthCurve(localAnalysis.progress);

  try {
    const response = await fetchBackendJson("/api/ai/self-analysis", {
      method: "POST"
    });
    const bullets = [...(response.resumeBullets ?? [])];

    if (response.strengths?.length) {
      bullets.push(`优势标签：${response.strengths.join("、")}`);
    }

    if (response.suggestions?.length) {
      bullets.push(`下一步建议：${response.suggestions.join("；")}`);
    }

    renderAnalysisContent(response.summary, bullets);
  } catch (error) {
    renderAnalysisContent(
      "后端 AI 暂时没有返回结果，已退回本地分析。",
      [...localAnalysis.bullets, `错误信息：${error.message}`]
    );
  } finally {
    analysisGenerate.disabled = false;
    analysisGenerate.textContent = originalText;
  }
}

function renderAnalysisContent(summary, bullets) {
  resumeSummary.textContent = summary;
  resumeBullets.innerHTML = "";

  bullets.forEach((text) => {
    const item = document.createElement("li");
    item.textContent = text;
    resumeBullets.append(item);
  });
}

async function renderAiRecommendations(showReason) {
  const need = normalize(aiNeed.value);
  aiResult.innerHTML = "";

  if (!showReason) {
    getAiRecommendations(need).forEach((item) => {
      aiResult.append(createStudentEventCard(item, true));
    });
    return;
  }

  const originalText = aiRecommend.textContent;
  aiRecommend.disabled = true;
  aiRecommend.textContent = "推荐中...";
  aiResult.append(createAiNote("正在连接后端 AI 推荐 Agent，请稍等。"));

  try {
    const toolContext = await buildMcpToolContext();
    const response = await fetchBackendJson("/api/ai/event-recommendations", {
      method: "POST",
      body: JSON.stringify({
        need: need || aiNeed.placeholder,
        category: studentCategory === "all" ? "" : studentCategory,
        benefitType: studentBenefit === "all" ? "" : mapFrontendBenefitType(studentBenefit),
        location: "",
        toolContext
      })
    });

    aiResult.innerHTML = "";
    aiResult.append(createAiNote(`后端 AI 已返回 ${response.recommendations?.length ?? 0} 个结果，模式：${response.mode}。${response.message ?? ""}`));
    const toolNote = formatMcpToolContext(response.toolContext);
    if (toolNote) {
      aiResult.append(createAiNote(toolNote));
    }

    if (!response.recommendations?.length) {
      aiResult.append(createAiNote("暂无匹配事件，可以换一个需求描述再试。"));
      return;
    }

    response.recommendations.forEach((recommendation) => {
      aiResult.append(createAiRecommendationCard(recommendation));
    });
  } catch (error) {
    const fallbackRecommendations = getAiRecommendations(need);
    aiResult.innerHTML = "";
    aiResult.append(createAiNote(`后端 AI 暂时不可用，已退回本地推荐。错误信息：${error.message}`));
    fallbackRecommendations.forEach((item) => {
      aiResult.append(createStudentEventCard(item, true));
    });
  } finally {
    aiRecommend.disabled = false;
    aiRecommend.textContent = originalText;
  }
}

async function renderAiPlans() {
  const goal = normalize(aiNeed.value) || aiNeed.placeholder;
  const originalText = aiPlanRecommend.textContent;
  aiPlanRecommend.disabled = true;
  aiPlanRecommend.textContent = "规划中...";
  aiResult.innerHTML = "";
  aiResult.append(createAiNote("正在连接后端计划推荐 Agent，请稍等。"));

  try {
    const toolContext = await buildMcpToolContext();
    const response = await fetchBackendJson("/api/ai/action-plans", {
      method: "POST",
      body: JSON.stringify({
        goal,
        horizonDays: 21,
        intensity: "normal",
        location: "",
        toolContext
      })
    });

    aiResult.innerHTML = "";
    aiResult.append(createAiNote(`后端计划 Agent 已返回 ${response.plans?.length ?? 0} 份计划，模式：${response.mode}。${response.message ?? ""}`));
    const toolNote = formatMcpToolContext(response.toolContext);
    if (toolNote) {
      aiResult.append(createAiNote(toolNote));
    }
    latestRecommendedPlans = response.plans ?? [];
    latestRecommendedPlans.forEach((plan, index) => {
      aiResult.append(createPlanSummaryCard(plan, index));
    });
    if (latestRecommendedPlans.length > 0) {
      openPlanModal(0);
    }
  } catch (error) {
    aiResult.innerHTML = "";
    aiResult.append(createAiNote(`计划推荐暂时不可用：${error.message}`));
  } finally {
    aiPlanRecommend.disabled = false;
    aiPlanRecommend.textContent = originalText;
  }
}

function createPlanCard(plan) {
  const card = document.createElement("article");
  const title = document.createElement("h3");
  const meta = document.createElement("p");
  const summary = document.createElement("p");
  const list = document.createElement("ol");

  card.className = "event-card student-event-card";
  title.className = "event-title";
  meta.className = "event-date";
  summary.className = "event-content";
  title.textContent = plan.title;
  meta.textContent = `风格：${plan.style}`;
  summary.textContent = plan.summary;

  (plan.steps ?? []).forEach((step) => {
    const item = document.createElement("li");
    const eventHint = step.eventId ? ` #${step.eventId}` : "";
    item.textContent = `${step.dateLabel}：${step.title}${eventHint}｜${step.scheduleHint}｜${step.reason}`;
    list.append(item);
  });

  card.append(meta, title, summary, list);

  if (plan.warnings?.length) {
    card.append(createAiNote(`提醒：${plan.warnings.join("；")}`));
  }

  return card;
}

function createPlanSummaryCard(plan, index) {
  const card = document.createElement("article");
  const title = document.createElement("h3");
  const meta = document.createElement("p");
  const summary = document.createElement("p");
  const action = document.createElement("button");

  card.className = "event-card student-event-card";
  title.className = "event-title";
  meta.className = "event-date";
  summary.className = "event-content";
  action.className = "primary-button";
  action.type = "button";
  action.textContent = "查看流程图";
  action.addEventListener("click", () => openPlanModal(index));

  title.textContent = plan.title ?? `计划 ${index + 1}`;
  meta.textContent = `风格：${plan.style ?? "未指定"} · ${plan.steps?.length ?? 0} 步${formatPlanQuality(plan)}`;
  summary.textContent = plan.summary ?? "点击查看完整计划流程。";
  card.append(meta, title, summary, action);

  if (plan.warnings?.length) {
    card.append(createAiNote(`提醒：${plan.warnings.join("；")}`));
  }

  return card;
}

function openPlanModal(index = 0) {
  if (!latestRecommendedPlans.length || !planModal || !planTabs || !planFlow) {
    return;
  }

  activePlanIndex = Math.min(Math.max(index, 0), latestRecommendedPlans.length - 1);
  renderPlanModal();
  planModal.hidden = false;
  planModalClose?.focus();
}

function closePlanModal() {
  if (planModal) {
    planModal.hidden = true;
  }
}

function renderPlanModal() {
  const plan = latestRecommendedPlans[activePlanIndex];
  planTabs.innerHTML = "";
  planFlow.innerHTML = "";
  if (planImportSchedule) {
    planImportSchedule.disabled = !plan || !(plan.steps ?? []).length;
  }

  latestRecommendedPlans.forEach((item, index) => {
    const button = document.createElement("button");
    button.className = "plan-tab-button";
    button.type = "button";
    button.dataset.planIndex = String(index);
    button.classList.toggle("is-active", index === activePlanIndex);
    button.textContent = item.title ?? `计划 ${index + 1}`;
    planTabs.append(button);
  });

  const summary = document.createElement("p");
  summary.className = "plan-flow-summary";
  summary.textContent = `${plan?.summary ?? "这份计划还没有摘要。"}${formatPlanQuality(plan)}`;
  planFlow.append(summary);

  (plan?.steps ?? []).forEach((step, index) => {
    planFlow.append(createPlanFlowStep(step, index));
  });

  if (plan?.warnings?.length) {
    const warning = document.createElement("div");
    warning.className = "plan-warning";
    warning.textContent = `提醒：${plan.warnings.join("；")}`;
    planFlow.append(warning);
  }

  if (plan?.agentTrace?.length) {
    const trace = document.createElement("div");
    trace.className = "plan-agent-trace";
    trace.textContent = `Agent链路：${plan.agentTrace.join(" → ")}`;
    planFlow.append(trace);
  }
}

function formatPlanQuality(plan) {
  return Number.isFinite(plan?.qualityScore) ? ` · 质量分 ${plan.qualityScore}` : "";
}

function createPlanFlowStep(step, index) {
  const card = document.createElement("article");
  const number = document.createElement("div");
  const body = document.createElement("div");
  const date = document.createElement("p");
  const title = document.createElement("h3");
  const schedule = document.createElement("p");
  const reason = document.createElement("p");

  card.className = "plan-flow-step";
  number.className = "plan-step-number";
  body.className = "plan-step-body";
  date.className = "plan-step-date";
  title.className = "plan-step-title";
  schedule.className = "plan-step-schedule";
  reason.className = "plan-step-reason";

  number.textContent = String(index + 1).padStart(2, "0");
  date.textContent = step.dateLabel ?? "时间待定";
  title.textContent = step.eventId ? `${step.title ?? "任务"} #${step.eventId}` : (step.title ?? "任务");
  schedule.textContent = step.scheduleHint ?? "安排待细化";
  reason.textContent = step.reason ?? "推荐理由待补充";

  body.append(date, title, schedule, reason);
  card.append(number, body);
  return card;
}

async function importActivePlanToSchedule() {
  const plan = latestRecommendedPlans[activePlanIndex];
  if (!plan || !(plan.steps ?? []).length) {
    return;
  }

  const originalText = planImportSchedule.textContent;
  planImportSchedule.disabled = true;
  planImportSchedule.textContent = "写入中...";

  try {
    const payload = {
      title: plan.title ?? `计划 ${activePlanIndex + 1}`,
      style: plan.style ?? "",
      goal: normalize(aiNeed.value) || plan.summary || plan.title || "AI 推荐计划",
      steps: (plan.steps ?? []).map((step, index) => ({
        order: Number.isInteger(step.order) ? step.order : index + 1,
        dateLabel: step.dateLabel ?? "",
        title: step.title ?? `步骤 ${index + 1}`,
        itemType: step.itemType ?? "STUDY",
        eventId: step.eventId ?? null,
        scheduleHint: step.scheduleHint ?? "",
        reason: step.reason ?? ""
      }))
    };
    const response = await fetchBackendJson("/api/schedule/import-ai-plan", {
      method: "POST",
      body: JSON.stringify(payload)
    });

    const firstItem = response.items?.[0];
    if (firstItem?.startTime) {
      scheduleMonth = new Date(firstItem.startTime);
    }
    await reloadBackendData();
    activeModule = "schedule";
    closePlanModal();
    renderAll();
    showScheduleMessage(`已从 AI 计划写入 ${response.importedCount ?? 0} 个日程。`, "success");
  } catch (error) {
    planFlow.append(createAiNote(`写入日程失败：${error.message}`));
  } finally {
    planImportSchedule.disabled = false;
    planImportSchedule.textContent = originalText;
  }
}

async function buildMcpToolContext() {
  const timezone = Intl.DateTimeFormat().resolvedOptions().timeZone || "Asia/Tokyo";
  const context = {
    timezone,
    clientNow: new Date().toISOString()
  };
  const location = await readBrowserLocation();
  if (location) {
    context.latitude = location.latitude;
    context.longitude = location.longitude;
    context.locationText = location.locationText;
  }
  return context;
}

function readBrowserLocation() {
  if (cachedMcpLocation) {
    return Promise.resolve(cachedMcpLocation);
  }
  if (!navigator.geolocation) {
    return Promise.resolve(null);
  }
  return new Promise((resolve) => {
    navigator.geolocation.getCurrentPosition(
      (position) => {
        cachedMcpLocation = {
          latitude: position.coords.latitude,
          longitude: position.coords.longitude,
          locationText: `lat ${position.coords.latitude.toFixed(5)}, lng ${position.coords.longitude.toFixed(5)}`
        };
        resolve(cachedMcpLocation);
      },
      () => resolve(null),
      {
        enableHighAccuracy: false,
        timeout: 1200,
        maximumAge: 10 * 60 * 1000
      }
    );
  });
}

function formatMcpToolContext(toolContext) {
  if (!toolContext?.currentTime) {
    return "";
  }
  const time = toolContext.currentTime;
  const location = toolContext.location;
  const parts = [`MCP工具：当前日期 ${time.localDate}，时区 ${time.timezone}`];
  if (location?.label) {
    parts.push(`定位来源 ${location.source}：${location.label}`);
  }
  return parts.join("；");
}

function createAiNote(text) {
  const note = document.createElement("p");
  note.className = "ai-note";
  note.textContent = text;
  return note;
}

function createAiRecommendationCard(recommendation) {
  const item = mapBackendEventToFrontend(recommendation.event);
  const card = createStudentEventCard(item, true);
  const reason = createAiNote(`推荐理由：${recommendation.reason ?? "该事件与当前需求较匹配。"}`);
  const meta = createAiNote(`匹配分：${recommendation.score ?? "-"} / 100，置信度：${recommendation.confidence ?? "UNKNOWN"}`);
  card.append(reason);
  card.append(meta);

  if (recommendation.evidence?.length) {
    const evidence = createAiNote(`证据：${recommendation.evidence.join("；")}`);
    card.append(evidence);
  }

  return card;
}

function mapBackendEventToFrontend(event) {
  const localId = String(event.id);
  const localEvent = events.find((item) => item.id === localId);

  if (localEvent) {
    localEvent.endTime = event.endTime ?? localEvent.endTime;
    localEvent.expired = Boolean(event.expired);
    return localEvent;
  }

  return normalizeEvent({
    id: localId,
    title: event.title,
    organization: event.organizationName,
    category: event.category,
    date: event.startTime,
    endTime: event.endTime,
    expired: Boolean(event.expired),
    location: event.location,
    content: event.content,
    benefitType: mapBackendBenefitType(event.benefitTypeCode),
    skill: event.skill,
    money: event.moneyAmount ?? "",
    createdByUserId: event.createdByUserId,
    createdAt: event.createdAt
  });
}

function mapBackendBenefitType(code) {
  if (code === "MONEY") {
    return "money";
  }

  if (code === "BOTH") {
    return "both";
  }

  return "skill";
}

async function handleStudentEventAction(event) {
  const followButton = event.target.closest(".follow-button");
  const reserveButton = event.target.closest(".reserve-button");

  if (followButton) {
    await toggleOrganization(followButton.dataset.organization);
    return;
  }

  if (reserveButton && !reserveButton.disabled) {
    await reserveEvent(reserveButton.dataset.eventId);
  }
}

async function handleReservationAction(event) {
  const scanButton = event.target.closest(".scan-button");
  const cancelButton = event.target.closest(".cancel-reservation-button");

  if (!scanButton && !cancelButton) {
    return;
  }

  const card = event.target.closest(".reservation-card");

  if (scanButton) {
    await completeReservation(card.dataset.reservationId);
    return;
  }

  await cancelReservation(card.dataset.reservationId);
}

async function handleScheduleAction(event) {
  const editButton = event.target.closest(".edit-schedule-button");
  const deleteButton = event.target.closest(".delete-schedule-button");
  const scheduleItem = event.target.closest(".schedule-item");

  if (!editButton && !deleteButton && !scheduleItem) {
    return;
  }

  const wrapper = scheduleItem;
  const item = scheduleItems.find((entry) => entry.id === wrapper.dataset.scheduleId);

  if (!item) {
    return;
  }

  if (deleteButton) {
    await deleteScheduleItem(item.id);
    return;
  }

  if (editButton || scheduleItem) {
    startEditScheduleItem(item);
    return;
  }
}

function startScheduleSelection(event) {
  const slot = event.target.closest(".schedule-time-slot");
  if (!slot || event.button !== 0) {
    return;
  }

  event.preventDefault();
  scheduleDragState = {
    dateKey: slot.dataset.date,
    startSlot: Number(slot.dataset.slotIndex),
    currentSlot: Number(slot.dataset.slotIndex)
  };
  scheduleCalendar.classList.add("is-selecting");
  updateScheduleSelectionClasses();
}

function updateScheduleSelection(event) {
  if (!scheduleDragState) {
    return;
  }

  const target = document.elementFromPoint(event.clientX, event.clientY);
  const slot = target?.closest(".schedule-time-slot");
  if (!slot || slot.dataset.date !== scheduleDragState.dateKey) {
    return;
  }

  const nextSlot = Number(slot.dataset.slotIndex);
  if (Number.isNaN(nextSlot) || nextSlot === scheduleDragState.currentSlot) {
    return;
  }

  scheduleDragState.currentSlot = nextSlot;
  updateScheduleSelectionClasses();
}

function finishScheduleSelection(event) {
  if (!scheduleDragState) {
    return;
  }

  event.preventDefault();
  const { dateKey, startSlot, currentSlot } = scheduleDragState;
  scheduleDragState = null;
  scheduleCalendar.classList.remove("is-selecting");
  const firstSlot = Math.min(startSlot, currentSlot);
  const lastSlot = Math.max(startSlot, currentSlot) + 1;
  applyScheduleDraftFromSelection(dateKey, firstSlot, lastSlot);
  updateScheduleSelectionClasses();
}

function updateScheduleSelectionClasses() {
  scheduleCalendar.querySelectorAll(".schedule-time-slot.is-selecting").forEach((slot) => {
    slot.classList.remove("is-selecting");
  });

  if (!scheduleDragState) {
    return;
  }

  const firstSlot = Math.min(scheduleDragState.startSlot, scheduleDragState.currentSlot);
  const lastSlot = Math.max(scheduleDragState.startSlot, scheduleDragState.currentSlot);
  scheduleCalendar
    .querySelectorAll(`.schedule-time-slot[data-date="${scheduleDragState.dateKey}"]`)
    .forEach((slot) => {
      const slotIndex = Number(slot.dataset.slotIndex);
      slot.classList.toggle("is-selecting", slotIndex >= firstSlot && slotIndex <= lastSlot);
    });
}

function applyScheduleDraftFromSelection(dateKey, startSlot, endSlot) {
  const start = dateAndSlotToDate(dateKey, startSlot);
  const end = dateAndSlotToDate(dateKey, endSlot);
  const selectedChallenge = challenges.find((item) => item.id === scheduleChallengeSelect.value);

  scheduleForm.elements.startTime.value = toDateTimeInput(start);
  scheduleForm.elements.endTime.value = toDateTimeInput(end);

  if (!normalize(scheduleForm.elements.title.value)) {
    scheduleForm.elements.title.value = selectedChallenge?.title ?? "学习时间";
  }

  if (selectedChallenge && !normalize(scheduleForm.elements.notes.value)) {
    scheduleForm.elements.notes.value = `${selectedChallenge.goal}。${selectedChallenge.description}`;
  }

  scheduleSubmit.textContent = scheduleEditingId ? "保存修改" : "保存这个时间块";
  scheduleForm.elements.title.focus();
  showScheduleMessage(`已选中 ${formatScheduleDraftLabel(start, end)}，补充标题后保存。`, "success");
}

function dateAndSlotToDate(dateKey, slotIndex) {
  const date = new Date(`${dateKey}T00:00:00`);
  date.setMinutes(SCHEDULE_DAY_START_HOUR * 60 + slotIndex * SCHEDULE_SLOT_MINUTES, 0, 0);
  return date;
}

function formatScheduleDraftLabel(start, end) {
  const dateText = new Intl.DateTimeFormat("zh-CN", {
    month: "short",
    day: "numeric",
    weekday: "short"
  }).format(start);
  return `${dateText} ${formatTimeRange(start, end)}`;
}

async function handleChallengeAction(event) {
  const completeButton = event.target.closest(".complete-challenge-button");
  const cancelButton = event.target.closest(".cancel-challenge-button");

  if (!completeButton && !cancelButton) {
    return;
  }

  const card = event.target.closest(".challenge-card");
  const challengeId = card.dataset.challengeId;

  if (completeButton) {
    await completeChallenge(
      challengeId,
      normalize(card.querySelector(".challenge-did-input").value),
      normalize(card.querySelector(".challenge-learned-input").value)
    );
    return;
  }

  await cancelChallenge(challengeId);
}

async function handleHistoryAction(event) {
  const saveButton = event.target.closest(".save-reflection-button");

  if (!saveButton) {
    return;
  }

  const card = saveButton.closest(".history-card");
  const record = completedRecords.find((item) => item.id === card.dataset.recordId);

  if (!record) {
    return;
  }

  try {
    const updatedRecord = await fetchBackendJson(`/api/achievements/history/${record.id}/reflection`, {
      method: "PUT",
      body: JSON.stringify({
        did: normalize(card.querySelector(".did-input").value),
        learned: normalize(card.querySelector(".learned-input").value)
      })
    });
    Object.assign(record, mapBackendAchievementToCompletedRecord(updatedRecord));
    growthTagDetails = new Map();
    growthTags = (await fetchBackendJson("/api/achievements/growth-tags")).map(mapBackendGrowthTag);
    saveButton.textContent = "已保存";
    renderAbilityMap();
    renderSelfAnalysis();
  } catch (error) {
    saveButton.textContent = "保存失败";
    showAuthMessage(error.message, "error");
  }
}

async function saveScheduleItem() {
  const formData = new FormData(scheduleForm);
  const challengeId = normalize(formData.get("challengeId"));
  const payload = {
    title: normalize(formData.get("title")),
    startTime: normalize(formData.get("startTime")),
    endTime: normalize(formData.get("endTime")),
    location: normalize(formData.get("location")),
    notes: normalize(formData.get("notes"))
  };

  if (!payload.title || !payload.startTime || !payload.endTime) {
    showScheduleMessage("请填写标题、开始时间和结束时间。", "error");
    return;
  }

  try {
    const wasEditing = Boolean(scheduleEditingId);
    if (scheduleEditingId) {
      await fetchBackendJson(`/api/schedule/${scheduleEditingId}`, {
        method: "PUT",
        body: JSON.stringify(payload)
      });
    } else {
      await fetchBackendJson("/api/schedule", {
        method: "POST",
        body: JSON.stringify({
          ...payload,
          itemType: challengeId ? "CHALLENGE" : "CUSTOM",
          sourceId: challengeId ? Number(challengeId) : null
        })
      });
    }

    await reloadBackendData();
    resetScheduleForm();
    showScheduleMessage(wasEditing ? "日程已更新。" : "日程已保存。", "success");
    renderAll();
  } catch (error) {
    showScheduleMessage(`保存失败：${error.message}`, "error");
  }
}

function startEditScheduleItem(item) {
  scheduleEditingId = item.id;
  scheduleForm.elements.title.value = item.title;
  scheduleForm.elements.startTime.value = toDateTimeInput(item.startTime);
  scheduleForm.elements.endTime.value = toDateTimeInput(item.endTime);
  scheduleForm.elements.location.value = item.location ?? "";
  scheduleForm.elements.notes.value = item.notes ?? "";
  scheduleChallengeSelect.value = item.itemType === "CHALLENGE" && item.sourceId ? String(item.sourceId) : "";
  scheduleChallengeSelect.disabled = true;
  scheduleSubmit.textContent = "保存修改";
  scheduleForm.elements.title.focus();
  showScheduleMessage("正在编辑日程。", "success");
}

async function deleteScheduleItem(itemId) {
  try {
    await fetchBackendJson(`/api/schedule/${itemId}`, {
      method: "DELETE"
    });
    await reloadBackendData();
    resetScheduleForm();
    renderAll();
  } catch (error) {
    showScheduleMessage(`删除失败：${error.message}`, "error");
  }
}

async function sendCoachMessage() {
  const message = normalize(coachInput.value);

  if (!message) {
    return;
  }

  const originalText = coachSend.textContent;
  coachSend.disabled = true;
  coachSend.textContent = "发送中...";

  try {
    await fetchBackendJson("/api/coach/chat", {
      method: "POST",
      body: JSON.stringify({ message })
    });
    coachInput.value = "";
    await reloadBackendData();
    renderAll();
    showCoachMessage("教练已回复。", "success");
  } catch (error) {
    showCoachMessage(`发送失败：${error.message}`, "error");
  } finally {
    coachSend.disabled = false;
    coachSend.textContent = originalText;
  }
}

async function generateCoachLog() {
  const originalText = coachGenerateLog.textContent;
  coachGenerateLog.disabled = true;
  coachGenerateLog.textContent = "生成中...";

  try {
    await fetchBackendJson("/api/coach/logs/generate", {
      method: "POST",
      body: JSON.stringify({})
    });
    await reloadBackendData();
    renderAll();
    showCoachMessage("今日日志已生成。", "success");
  } catch (error) {
    showCoachMessage(`生成失败：${error.message}`, "error");
  } finally {
    coachGenerateLog.disabled = false;
    coachGenerateLog.textContent = originalText;
  }
}

function resetScheduleForm() {
  scheduleEditingId = "";
  scheduleForm.reset();
  scheduleChallengeSelect.disabled = false;
  scheduleSubmit.textContent = "保存日程";
  setDefaultScheduleTimes();
  showScheduleMessage("", "success");
}

function syncScheduleChallengeSelection() {
  const challenge = challenges.find((item) => item.id === scheduleChallengeSelect.value);

  if (!challenge) {
    return;
  }

  if (!normalize(scheduleForm.elements.title.value)) {
    scheduleForm.elements.title.value = challenge.title;
  }

  if (!normalize(scheduleForm.elements.notes.value)) {
    scheduleForm.elements.notes.value = `${challenge.goal}。${challenge.description}`;
  }
}

async function reserveEvent(eventId) {
  if (isReserved(eventId) || isCompleted(eventId)) {
    return;
  }

  try {
    await fetchBackendJson("/api/reservations", {
      method: "POST",
      body: JSON.stringify({ eventId: Number(eventId) })
    });
    await reloadBackendData();
    renderAll();
  } catch (error) {
    showAuthMessage(`预约失败：${error.message}`, "error");
  }
}

async function completeReservation(reservationId) {
  const reservation = reservations.find((item) => item.id === reservationId);

  if (!reservation) {
    return;
  }

  try {
    await fetchBackendJson("/api/reservations/scan-complete", {
      method: "POST",
      body: JSON.stringify({ qrToken: reservation.qrToken })
    });
    await reloadBackendData();
    activeModule = "achievement";
    activeAchievementTab = "history";
    syncModuleButtons();
    renderAll();
  } catch (error) {
    showAuthMessage(`扫码完成失败：${error.message}`, "error");
  }
}

async function cancelReservation(reservationId) {
  try {
    await fetchBackendJson(`/api/reservations/${reservationId}`, {
      method: "DELETE"
    });
    await reloadBackendData();
    renderAll();
  } catch (error) {
    showAuthMessage(`取消预约失败：${error.message}`, "error");
  }
}

async function completeChallenge(challengeId, did, learned) {
  try {
    await fetchBackendJson(`/api/challenges/${challengeId}/complete`, {
      method: "POST",
      body: JSON.stringify({ did, learned })
    });
    await reloadBackendData();
    activeModule = "achievement";
    activeAchievementTab = "history";
    historyPage = 1;
    syncModuleButtons();
    renderAll();
  } catch (error) {
    showChallengeMessage(`完成失败：${error.message}`, "error");
  }
}

async function cancelChallenge(challengeId) {
  try {
    await fetchBackendJson(`/api/challenges/${challengeId}`, {
      method: "DELETE"
    });
    await reloadBackendData();
    renderAll();
  } catch (error) {
    showChallengeMessage(`取消失败：${error.message}`, "error");
  }
}

async function toggleOrganization(organization) {
  if (!organization) {
    return;
  }

  try {
    if (followedOrganizations.includes(organization)) {
      await fetchBackendJson(`/api/follows/${encodeURIComponent(organization)}`, {
        method: "DELETE"
      });
    } else {
      await fetchBackendJson("/api/follows", {
        method: "POST",
        body: JSON.stringify({ organizationName: organization })
      });
    }
    await reloadBackendData();
    renderAll();
  } catch (error) {
    showAuthMessage(`关注操作失败：${error.message}`, "error");
  }
}

function getSocialEvents() {
  const ownEvents = events.filter((item) => item.createdByUserId === activeUserId && !isExpiredEvent(item));

  if (socialFilter === "all") {
    return ownEvents;
  }

  if (socialFilter === "skill") {
    return ownEvents.filter((item) => item.benefitType === "skill" || item.benefitType === "both");
  }

  return ownEvents.filter(hasMoney);
}

function getStudentEvents() {
  return events.filter((item) => {
    if (isExpiredEvent(item)) {
      return false;
    }
    const matchesCategory = studentCategory === "all" || item.category === studentCategory;
    const matchesBenefit =
      studentBenefit === "all" ||
      (studentBenefit === "skill" && (item.benefitType === "skill" || item.benefitType === "both")) ||
      (studentBenefit === "money" && hasMoney(item));
    const matchesSearch = !searchTerm || getSearchText(item).includes(searchTerm);

    return matchesCategory && matchesBenefit && matchesSearch;
  });
}

function getAiRecommendations(need) {
  const scoredEvents = events
    .filter((item) => !isCompleted(item.id) && !isExpiredEvent(item))
    .map((item) => ({ item, score: scoreEvent(item, need) }))
    .sort((a, b) => b.score - a.score || compareDate(a.item.date, b.item.date));

  const positiveMatches = scoredEvents.filter((entry) => entry.score > 0).map((entry) => entry.item);

  if (positiveMatches.length > 0) {
    return positiveMatches.slice(0, 3);
  }

  return scoredEvents.map((entry) => entry.item).slice(0, 3);
}

function scoreEvent(item, need) {
  if (!need) {
    return 0;
  }

  const text = getSearchText(item);
  const normalizedNeed = need.toLowerCase();
  const terms = normalizedNeed.split(/[\s,，。;；、]+/).filter(Boolean);
  let score = 0;

  terms.forEach((term) => {
    if (text.includes(term)) {
      score += 5;
    } else if (term.length >= 2 && text.includes(term.slice(0, 2))) {
      score += 2;
    }
  });

  const moneyWords = ["报酬", "金钱", "钱", "兼职", "有偿"];
  const onlineWords = ["线上", "远程", "在家"];
  const languageWords = ["日语", "翻译", "沟通"];
  const researchWords = ["研究", "调研", "访谈", "问卷"];
  const operationWords = ["运营", "活动", "现场", "执行"];

  if (containsAny(normalizedNeed, moneyWords) && hasMoney(item)) {
    score += 5;
  }

  if (containsAny(normalizedNeed, onlineWords) && (item.category === "线上" || item.location.includes("线上"))) {
    score += 4;
  }

  if (containsAny(normalizedNeed, languageWords) && containsAny(text, languageWords)) {
    score += 4;
  }

  if (containsAny(normalizedNeed, researchWords) && item.category === "研究") {
    score += 4;
  }

  if (containsAny(normalizedNeed, operationWords) && containsAny(text, operationWords)) {
    score += 3;
  }

  return score;
}

function createStudentEventCard(item, compact = false) {
  const card = studentEventTemplate.content.firstElementChild.cloneNode(true);
  fillEventCard(card, item);

  const followButton = card.querySelector(".follow-button");
  const reserveButton = card.querySelector(".reserve-button");
  followButton.dataset.organization = item.organization;
  reserveButton.dataset.eventId = item.id;
  syncFollowButton(followButton, item.organization);
  syncReserveButton(reserveButton, item.id);

  if (compact) {
    card.querySelector(".event-content").hidden = true;
  }

  return card;
}

function fillEventCard(card, item) {
  card.dataset.eventId = item.id;
  card.querySelector(".event-date").textContent = formatEventDateRange(item.date, item.endTime);
  card.querySelector(".event-title").textContent = item.title;
  card.querySelector(".event-meta").textContent = `${item.organization} · ${item.category}`;
  card.querySelector(".event-location").textContent = item.location;
  card.querySelector(".event-content").textContent = item.content;
  card.querySelector(".event-benefits").append(...createBenefitChips(item));
}

function createBenefitChips(item) {
  const chips = [createChip(item.category, "category")];

  if (item.skill && (item.benefitType === "skill" || item.benefitType === "both")) {
    chips.push(createChip(`技能：${item.skill}`, "skill"));
  }

  if (item.money && hasMoney(item)) {
    chips.push(createChip(`报酬：¥${Number(item.money).toLocaleString("ja-JP")}`, "money"));
  }

  return chips;
}

function createChip(text, type) {
  const chip = document.createElement("span");
  chip.className = `benefit-chip ${type}`;
  chip.textContent = text;
  return chip;
}

function createTagChip(text) {
  const chip = document.createElement("span");
  chip.className = "tag-chip";
  chip.textContent = text;
  return chip;
}

function syncFollowButton(button, organization) {
  const following = followedOrganizations.includes(organization);
  button.classList.toggle("is-following", following);
  button.textContent = following ? "已关注" : "关注";
}

function syncReserveButton(button, eventId) {
  const reserved = isReserved(eventId);
  const completed = isCompleted(eventId);

  button.classList.toggle("is-reserved", reserved);
  button.classList.toggle("is-completed", completed);
  button.disabled = reserved || completed;
  button.textContent = completed ? "已完成" : reserved ? "已预约" : "预约";
}

function buildOrganizationProfile(name) {
  const orgEvents = events.filter((item) => item.organization === name);
  const backendProfile = backendOrganizations.find((item) => item.name === name);
  const defaultProfile = organizationDefaults[name] ?? {};
  const profile = backendProfile ?? defaultProfile;

  if (orgEvents.length === 0 && !profile.summary) {
    return null;
  }

  const orgCompleted = completedRecords.filter((record) => getRecordEvent(record).organization === name).length;
  const orgReserved = reservations.filter((reservation) => getReservationEvent(reservation)?.organization === name).length;
  const orgCategories = [...new Set(orgEvents.map((item) => item.category).filter(Boolean))];

  return {
    name,
    type: profile.type ?? "发布组织",
    summary:
      profile.summary ??
      `${orgEvents.length} 个事件，${orgReserved} 个预约，${orgCompleted} 个完成记录。`,
    tags: orgCategories.length > 0 ? orgCategories : ["关注中"]
  };
}

function buildSelfAnalysis() {
  const orderedRecords = getOrderedCompletedRecords().reverse();
  const progress = buildGrowthProgress(orderedRecords);

  if (orderedRecords.length === 0) {
    return {
      summary: "完成活动或挑战并写下经历后，这里会生成个人成长总结。",
      bullets: ["暂无可分析的实践或挑战记录。"],
      progress
    };
  }

  const counts = getCategoryCounts();
  const topCategories = counts
    .slice(0, 3)
    .map((item) => item.category)
    .join("、");
  const dimensionScores = getDimensionTotals(orderedRecords);
  const topDimensions = dimensionScores
    .slice(0, 3)
    .map((item) => item.label)
    .join("、");
  const latestRecord = orderedRecords[orderedRecords.length - 1];
  const latestEvent = getRecordEvent(latestRecord);
  const reflections = orderedRecords
    .flatMap((record) => [record.did, record.learned])
    .map(normalize)
    .filter(Boolean);

  const summary = `你已经完成 ${orderedRecords.length} 个实践或挑战，覆盖 ${counts.length} 个类别，经历集中在 ${topCategories || "多元实践"}。从事件内容和个人记录看，你的优势正在向 ${topDimensions || "综合实践能力"} 累积，最近一次代表经历是“${latestEvent.title}”。`;
  const bullets = [
    `实践领域：${topCategories || "暂无明显集中领域"}。`,
    `能力画像：${topDimensions || "等待更多完成记录"}。`,
    `代表经历：${latestEvent.organization} 的“${latestEvent.title}”，对应 ${latestEvent.skill || latestEvent.category}。`
  ];

  if (reflections.length > 0) {
    bullets.push(`自我叙述关键词：${extractKeywords(reflections.join(" ")).join("、")}。`);
  }

  return { summary, bullets, progress };
}

function buildGrowthProgress(records) {
  const runningScores = Object.fromEntries(growthDimensions.map((dimension) => [dimension.key, 0]));
  const points = Object.fromEntries(growthDimensions.map((dimension) => [dimension.key, []]));

  records.forEach((record, index) => {
    const text = getRecordSearchText(record);

    growthDimensions.forEach((dimension) => {
      const matchCount = dimension.words.filter((word) => text.includes(word)).length;
      runningScores[dimension.key] += Math.max(matchCount, 1);
      points[dimension.key].push({
        index,
        value: runningScores[dimension.key]
      });
    });
  });

  return points;
}

function renderGrowthCurve(progress) {
  growthCurve.innerHTML = "";

  if (completedRecords.length === 0) {
    const note = document.createElement("p");
    note.className = "ai-note";
    note.textContent = "暂无成长曲线。";
    growthCurve.append(note);
    return;
  }

  const width = 640;
  const height = 260;
  const margin = { top: 24, right: 28, bottom: 34, left: 42 };
  const usableWidth = width - margin.left - margin.right;
  const usableHeight = height - margin.top - margin.bottom;
  const maxValue = Math.max(
    ...growthDimensions.flatMap((dimension) => progress[dimension.key].map((point) => point.value)),
    1
  );
  const maxIndex = Math.max(completedRecords.length - 1, 1);
  const svg = createSvgElement("svg");
  const legend = document.createElement("div");

  svg.setAttribute("viewBox", `0 0 ${width} ${height}`);
  svg.setAttribute("class", "growth-svg");
  legend.className = "growth-legend";

  for (let lineIndex = 0; lineIndex <= 4; lineIndex += 1) {
    const y = margin.top + (usableHeight / 4) * lineIndex;
    const line = createSvgElement("line");
    line.setAttribute("x1", margin.left);
    line.setAttribute("x2", width - margin.right);
    line.setAttribute("y1", y);
    line.setAttribute("y2", y);
    line.setAttribute("stroke", "#eee9df");
    line.setAttribute("stroke-width", "1");
    svg.append(line);
  }

  growthDimensions.forEach((dimension) => {
    const points = progress[dimension.key].map((point) => {
      const x = margin.left + (point.index / maxIndex) * usableWidth;
      const y = margin.top + usableHeight - (point.value / maxValue) * usableHeight;
      return { x, y };
    });
    const polyline = createSvgElement("polyline");
    polyline.setAttribute("points", points.map((point) => `${point.x},${point.y}`).join(" "));
    polyline.setAttribute("fill", "none");
    polyline.setAttribute("stroke", dimension.color);
    polyline.setAttribute("stroke-width", "3");
    polyline.setAttribute("stroke-linecap", "round");
    polyline.setAttribute("stroke-linejoin", "round");
    svg.append(polyline);

    points.forEach((point) => {
      const circle = createSvgElement("circle");
      circle.setAttribute("cx", point.x);
      circle.setAttribute("cy", point.y);
      circle.setAttribute("r", "4");
      circle.setAttribute("fill", dimension.color);
      svg.append(circle);
    });

    const legendItem = document.createElement("span");
    const legendDot = document.createElement("span");
    legendItem.className = "legend-item";
    legendDot.className = "legend-dot";
    legendDot.style.background = dimension.color;
    legendItem.append(legendDot, document.createTextNode(dimension.label));
    legend.append(legendItem);
  });

  growthCurve.append(svg, legend);
}

function getCategoryCounts() {
  const map = new Map();

  completedRecords.forEach((record) => {
    const item = getRecordEvent(record);
    map.set(item.category, (map.get(item.category) ?? 0) + 1);
  });

  return [...map.entries()]
    .map(([category, count]) => ({ category, count }))
    .sort((a, b) => b.count - a.count || categories.indexOf(a.category) - categories.indexOf(b.category));
}

function getDimensionTotals(records) {
  return growthDimensions
    .map((dimension) => {
      const value = records.reduce((total, record) => {
        const text = getRecordSearchText(record);
        return total + dimension.words.filter((word) => text.includes(word)).length;
      }, 0);
      return {
        label: dimension.label,
        value
      };
    })
    .sort((a, b) => b.value - a.value);
}

function getOrderedCompletedRecords() {
  return [...completedRecords].sort((a, b) => compareDate(b.completedAt, a.completedAt));
}

function getPageItems(items, page) {
  const start = (page - 1) * PAGE_SIZE;
  return items.slice(start, start + PAGE_SIZE);
}

function clampPage(page, totalItems) {
  const totalPages = Math.max(Math.ceil(totalItems / PAGE_SIZE), 1);
  return Math.min(Math.max(page, 1), totalPages);
}

function renderPagination(container, totalItems, currentPage) {
  const totalPages = Math.ceil(totalItems / PAGE_SIZE);
  container.innerHTML = "";
  container.hidden = totalPages <= 1;

  if (totalPages <= 1) {
    return;
  }

  const prevButton = createPageButton("上一页", "prev", currentPage === 1);
  const indicator = document.createElement("span");
  const nextButton = createPageButton("下一页", "next", currentPage === totalPages);

  indicator.className = "page-indicator";
  indicator.textContent = `${currentPage} / ${totalPages}`;
  container.append(prevButton, indicator, nextButton);
}

function createPageButton(text, action, disabled) {
  const button = document.createElement("button");
  button.className = "page-button";
  button.type = "button";
  button.dataset.pageAction = action;
  button.disabled = disabled;
  button.textContent = text;
  return button;
}

function getReservationEvent(reservation) {
  return events.find((item) => item.id === reservation.eventId) ?? reservation.event;
}

function getRecordEvent(record) {
  return record.event ?? events.find((item) => item.id === record.eventId) ?? normalizeEvent({});
}

function isReserved(eventId) {
  return reservations.some((item) => item.eventId === eventId);
}

function isCompleted(eventId) {
  return completedRecords.some((item) => item.eventId === eventId);
}

function snapshotEvent(item) {
  return {
    id: item.id,
    title: item.title,
    organization: item.organization,
    category: item.category,
    date: item.date,
    endTime: item.endTime,
    location: item.location,
    content: item.content,
    benefitType: item.benefitType,
    skill: item.skill,
    money: item.money,
    createdAt: item.createdAt
  };
}

function createCompletedChallengeRecord(challenge) {
  const event = {
    id: challenge.id,
    title: challenge.title,
    organization: "个人挑战",
    category: challenge.category,
    date: challenge.completedAt,
    location: "自定义",
    content: challenge.description,
    benefitType: "skill",
    skill: challenge.goal,
    money: "",
    createdAt: challenge.createdAt
  };

  return {
    id: createEventId(),
    sourceType: "CHALLENGE",
    sourceId: challenge.id,
    eventId: challenge.id,
    event,
    reservedAt: "",
    completedAt: challenge.completedAt,
    did: challenge.did,
    learned: challenge.learned
  };
}

function renderScheduleChallengeOptions() {
  const selectedValue = scheduleChallengeSelect.value;
  scheduleChallengeSelect.innerHTML = "";

  const defaultOption = document.createElement("option");
  defaultOption.value = "";
  defaultOption.textContent = "自定义安排";
  scheduleChallengeSelect.append(defaultOption);

  challenges
    .filter((item) => item.status === "active")
    .forEach((challenge) => {
      const option = document.createElement("option");
      option.value = challenge.id;
      option.textContent = challenge.title;
      scheduleChallengeSelect.append(option);
    });

  if ([...scheduleChallengeSelect.options].some((option) => option.value === selectedValue)) {
    scheduleChallengeSelect.value = selectedValue;
  }
}

function buildCalendarDays(monthDate) {
  const firstDay = new Date(monthDate.getFullYear(), monthDate.getMonth(), 1);
  const mondayOffset = (firstDay.getDay() + 6) % 7;
  const start = new Date(firstDay);
  start.setDate(firstDay.getDate() - mondayOffset);

  return Array.from({ length: 42 }, (_, index) => {
    const day = new Date(start);
    day.setDate(start.getDate() + index);
    return day;
  });
}

function groupScheduleItemsByDate(items) {
  const map = new Map();

  items
    .slice()
    .sort((left, right) => compareDate(left.startTime, right.startTime))
    .forEach((item) => {
      const key = toDateKey(new Date(item.startTime));
      const group = map.get(key) ?? [];
      group.push(item);
      map.set(key, group);
    });

  return map;
}

function setDefaultScheduleTimes() {
  if (!scheduleForm) {
    return;
  }

  const start = new Date();
  start.setMinutes(0, 0, 0);
  start.setHours(start.getHours() + 1);
  const end = new Date(start);
  end.setHours(end.getHours() + 1);
  scheduleForm.elements.startTime.value = toDateTimeInput(start);
  scheduleForm.elements.endTime.value = toDateTimeInput(end);
}

async function bootstrapApp() {
  await reloadBackendData();
  syncAuthUi();
  renderAll();
}

async function reloadBackendData() {
  try {
    const [eventData, organizationData] = await Promise.all([
      fetchPublicJson("/api/events"),
      fetchPublicJson("/api/organizations")
    ]);
    events = eventData.map(mapBackendEventToFrontend);
    backendOrganizations = organizationData;

    if (!authSession?.token) {
      followedOrganizations = [];
      reservations = [];
      completedRecords = [];
      growthTags = [];
      growthTagDetails = new Map();
      selectedGrowthTagId = "";
      loadingGrowthTagDetailId = "";
      challenges = [];
      scheduleItems = [];
      coachMessagesData = [];
      coachLogs = [];
      return;
    }

    const [followData, reservationData, challengeData, historyData, growthTagData, scheduleData, coachMessageData, coachLogData] = await Promise.all([
      fetchBackendJson("/api/follows"),
      fetchBackendJson("/api/reservations"),
      fetchBackendJson("/api/challenges"),
      fetchBackendJson("/api/achievements/history"),
      fetchBackendJson("/api/achievements/growth-tags"),
      fetchBackendJson("/api/schedule"),
      fetchBackendJson("/api/coach/messages"),
      fetchBackendJson("/api/coach/logs")
    ]);
    followedOrganizations = followData.map((item) => item.organizationName);
    reservations = reservationData.map(mapBackendReservationToFrontend);
    challenges = challengeData.map(mapBackendChallengeToFrontend);
    completedRecords = historyData.map(mapBackendAchievementToCompletedRecord);
    growthTags = growthTagData.map(mapBackendGrowthTag);
    growthTagDetails = new Map();
    loadingGrowthTagDetailId = "";
    selectedGrowthTagId = growthTags.some((tag) => tag.id === selectedGrowthTagId)
      ? selectedGrowthTagId
      : growthTags[0]?.id ?? "";
    scheduleItems = scheduleData.map(mapBackendScheduleToFrontend);
    coachMessagesData = coachMessageData;
    coachLogs = coachLogData;
  } catch (error) {
    showAuthMessage(`后端连接失败：${error.message}`, "error");
  }
}

async function getBackendAuthToken() {
  if (authSession?.token) {
    backendAuthToken = authSession.token;
    return backendAuthToken;
  }

  throw new Error("请先登录后再操作。");
}

async function fetchPublicJson(path, options = {}) {
  const headers = new Headers(options.headers ?? {});

  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  return parseBackendResponse(response);
}

async function fetchBackendJson(path, options = {}, retry = true) {
  const token = await getBackendAuthToken();
  const headers = new Headers(options.headers ?? {});
  headers.set("Authorization", `Bearer ${token}`);

  if (options.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...options,
    headers
  });

  if (response.status === 401 && retry) {
    backendAuthToken = "";
    if (authSession?.token) {
      authSession = null;
      activeUserId = "guest";
      localStorage.removeItem(AUTH_STORAGE_KEY);
    }
    return fetchBackendJson(path, options, false);
  }

  return parseBackendResponse(response);
}

async function parseBackendResponse(response) {
  if (!response.ok) {
    const message = await response.text();
    let parsedMessage = "";

    try {
      const body = JSON.parse(message);
      parsedMessage = body.message ?? "";
    } catch {
      parsedMessage = "";
    }

    throw new Error(parsedMessage || message || `HTTP ${response.status}`);
  }

  if (response.status === 204) {
    return null;
  }

  const text = await response.text();
  return text ? JSON.parse(text) : null;
}

function mapBackendReservationToFrontend(reservation) {
  return {
    id: String(reservation.id),
    eventId: String(reservation.event.id),
    event: mapBackendEventToFrontend(reservation.event),
    qrToken: reservation.qrToken,
    reservedAt: reservation.reservedAt,
    completedAt: reservation.completedAt,
    status: reservation.status
  };
}

function mapBackendChallengeToFrontend(challenge) {
  return {
    id: String(challenge.id),
    title: challenge.title,
    category: challenge.category,
    goal: challenge.goal,
    description: challenge.description,
    status: String(challenge.status ?? "ACTIVE").toLowerCase(),
    createdAt: challenge.createdAt,
    completedAt: challenge.completedAt ?? "",
    did: challenge.did ?? "",
    learned: challenge.learned ?? ""
  };
}

function mapBackendAchievementToCompletedRecord(record) {
  const event = normalizeEvent({
    id: `${record.sourceType.toLowerCase()}-${record.sourceId}`,
    title: record.eventTitle,
    organization: record.organizationName,
    category: record.category,
    date: record.eventStartTime,
    location: record.location,
    content: record.content,
    benefitType: mapBackendBenefitLabel(record.benefitType),
    skill: record.skill,
    money: record.moneyAmount ?? "",
    createdAt: record.completedAt
  });

  return {
    id: String(record.id),
    sourceType: record.sourceType,
    sourceId: String(record.sourceId),
    eventId: record.sourceType === "EVENT" ? String(record.eventId) : `${record.sourceType.toLowerCase()}-${record.sourceId}`,
    event,
    reservedAt: "",
    completedAt: record.completedAt,
    did: record.did ?? "",
    learned: record.learned ?? ""
  };
}

function mapBackendGrowthTag(tag) {
  return {
    id: String(tag.id),
    name: tag.name ?? "未命名能力",
    normalizedName: tag.normalizedName ?? "",
    description: tag.description ?? "",
    score: Number(tag.score ?? 0),
    evidenceCount: Number(tag.evidenceCount ?? 0),
    importanceScore: Number(tag.importanceScore ?? 0),
    lastUpdatedAt: tag.lastUpdatedAt ?? ""
  };
}

function mapBackendGrowthTagDetail(detail) {
  return {
    tag: mapBackendGrowthTag(detail.tag),
    evidences: (detail.evidences ?? []).map(mapBackendGrowthTagEvidence)
  };
}

function mapBackendGrowthTagEvidence(evidence) {
  return {
    id: String(evidence.id),
    tagId: String(evidence.tagId),
    recordId: String(evidence.recordId),
    sourceType: evidence.sourceType,
    sourceId: evidence.sourceId == null ? "" : String(evidence.sourceId),
    title: evidence.title ?? "未命名经历",
    summary: evidence.summary ?? "",
    did: evidence.did ?? "",
    learned: evidence.learned ?? "",
    scoreDelta: Number(evidence.scoreDelta ?? 0),
    milestone: Boolean(evidence.milestone),
    milestoneReason: evidence.milestoneReason ?? "",
    occurredAt: evidence.occurredAt ?? ""
  };
}

function mapBackendScheduleToFrontend(item) {
  return {
    id: String(item.id),
    itemType: item.itemType,
    sourceId: item.sourceId == null ? "" : String(item.sourceId),
    title: item.title,
    startTime: item.startTime,
    endTime: item.endTime,
    location: item.location ?? "",
    notes: item.notes ?? "",
    status: item.status,
    createdAt: item.createdAt,
    updatedAt: item.updatedAt
  };
}

function mapFrontendBenefitType(type) {
  if (type === "money") {
    return "MONEY";
  }

  if (type === "both") {
    return "BOTH";
  }

  return "SKILL";
}

function mapBackendBenefitLabel(label) {
  if (label === "金钱报酬") {
    return "money";
  }

  if (label === "两者都有") {
    return "both";
  }

  return "skill";
}

function loadEvents() {
  const savedEvents = localStorage.getItem(STORAGE_KEY);

  if (!savedEvents) {
    return sampleEvents.map(normalizeEvent);
  }

  try {
    const parsedEvents = JSON.parse(savedEvents);
    return Array.isArray(parsedEvents) ? parsedEvents.map(normalizeEvent) : sampleEvents.map(normalizeEvent);
  } catch {
    return sampleEvents.map(normalizeEvent);
  }
}

function normalizeEvent(item = {}, index = 0) {
  return {
    id: String(item.id ?? `saved-${index}`),
    title: item.title ?? "未命名事件",
    organization: item.organization ?? "未命名组织",
    category: item.category ?? "公益",
    date: item.date ?? "",
    endTime: item.endTime ?? "",
    location: item.location ?? "地点未定",
    content: item.content ?? "",
    benefitType: item.benefitType ?? "skill",
    skill: item.skill ?? "",
    money: item.money ?? "",
    createdByUserId: item.createdByUserId ?? "demo-social",
    createdAt: item.createdAt ?? new Date().toISOString(),
    expired: Boolean(item.expired)
  };
}

function isExpiredEvent(item) {
  if (item.expired) {
    return true;
  }
  const endTime = item.endTime || item.date;
  if (!endTime) {
    return false;
  }
  const endDate = new Date(endTime);
  return Number.isFinite(endDate.getTime()) && endDate.getTime() < Date.now();
}

function saveEvents() {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(events));
}

function loadFollowedOrganizations() {
  const savedOrganizations = localStorage.getItem(scopedStorageKey(FOLLOW_STORAGE_KEY));

  if (!savedOrganizations) {
    return defaultForDemoUser(defaultFollowedOrganizations);
  }

  try {
    const parsedOrganizations = JSON.parse(savedOrganizations);
    return Array.isArray(parsedOrganizations) ? parsedOrganizations : defaultFollowedOrganizations;
  } catch {
    return defaultFollowedOrganizations;
  }
}

function saveFollowedOrganizations() {
  localStorage.setItem(scopedStorageKey(FOLLOW_STORAGE_KEY), JSON.stringify(followedOrganizations));
}

function loadReservations() {
  const savedReservations = localStorage.getItem(scopedStorageKey(RESERVATION_STORAGE_KEY));

  if (!savedReservations) {
    return defaultForDemoUser(defaultReservations);
  }

  try {
    const parsedReservations = JSON.parse(savedReservations);
    return Array.isArray(parsedReservations) ? parsedReservations.map(normalizeReservation) : defaultReservations;
  } catch {
    return defaultReservations;
  }
}

function normalizeReservation(item = {}, index = 0) {
  return {
    id: item.id ?? `reservation-${index}`,
    eventId: item.eventId ?? item.event?.id ?? "",
    event: normalizeEvent(item.event ?? {}),
    reservedAt: item.reservedAt ?? new Date().toISOString()
  };
}

function saveReservations() {
  localStorage.setItem(scopedStorageKey(RESERVATION_STORAGE_KEY), JSON.stringify(reservations));
}

function loadCompletedRecords() {
  const savedRecords = localStorage.getItem(scopedStorageKey(COMPLETED_STORAGE_KEY));

  if (!savedRecords) {
    return defaultForDemoUser(defaultCompletedRecords);
  }

  try {
    const parsedRecords = JSON.parse(savedRecords);
    return Array.isArray(parsedRecords) ? parsedRecords.map(normalizeCompletedRecord) : defaultCompletedRecords;
  } catch {
    return defaultCompletedRecords;
  }
}

function normalizeCompletedRecord(item = {}, index = 0) {
  return {
    id: item.id ?? `completed-${index}`,
    sourceType: item.sourceType ?? "EVENT",
    sourceId: item.sourceId ?? item.eventId ?? item.event?.id ?? "",
    eventId: item.eventId ?? item.event?.id ?? "",
    event: normalizeEvent(item.event ?? {}),
    reservedAt: item.reservedAt ?? "",
    completedAt: item.completedAt ?? new Date().toISOString(),
    did: item.did ?? "",
    learned: item.learned ?? ""
  };
}

function saveCompletedRecords() {
  localStorage.setItem(scopedStorageKey(COMPLETED_STORAGE_KEY), JSON.stringify(completedRecords));
}

function loadChallenges() {
  const savedChallenges = localStorage.getItem(scopedStorageKey(CHALLENGE_STORAGE_KEY));

  if (!savedChallenges) {
    return defaultForDemoUser(defaultChallenges);
  }

  try {
    const parsedChallenges = JSON.parse(savedChallenges);
    return Array.isArray(parsedChallenges) ? parsedChallenges.map(normalizeChallenge) : defaultChallenges;
  } catch {
    return defaultChallenges;
  }
}

function normalizeChallenge(item = {}, index = 0) {
  return {
    id: item.id ?? `challenge-${index}`,
    title: item.title ?? "未命名挑战",
    category: item.category ?? "其他挑战",
    goal: item.goal ?? "",
    description: item.description ?? "",
    status: item.status ?? "active",
    createdAt: item.createdAt ?? new Date().toISOString(),
    completedAt: item.completedAt ?? "",
    did: item.did ?? "",
    learned: item.learned ?? ""
  };
}

function saveChallenges() {
  localStorage.setItem(scopedStorageKey(CHALLENGE_STORAGE_KEY), JSON.stringify(challenges));
}

function loadAuthSession() {
  const savedSession = localStorage.getItem(AUTH_STORAGE_KEY);

  if (!savedSession) {
    return null;
  }

  try {
    const parsedSession = JSON.parse(savedSession);
    return parsedSession?.token && parsedSession?.user?.userId ? parsedSession : null;
  } catch {
    return null;
  }
}

function loadLocalUsers() {
  const savedUsers = localStorage.getItem(LOCAL_USERS_STORAGE_KEY);

  if (!savedUsers) {
    return defaultLocalUsers;
  }

  try {
    const parsedUsers = JSON.parse(savedUsers);
    return Array.isArray(parsedUsers) ? parsedUsers : defaultLocalUsers;
  } catch {
    return defaultLocalUsers;
  }
}

function scopedStorageKey(key) {
  return `${key}:${activeUserId}`;
}

function defaultForDemoUser(defaultValue) {
  return activeUserId === "demo-student" ? defaultValue : [];
}

function syncBenefitFields() {
  const benefitType = new FormData(form).get("benefitType");
  const showSkill = benefitType === "skill" || benefitType === "both";
  const showMoney = benefitType === "money" || benefitType === "both";

  skillField.hidden = !showSkill;
  moneyField.classList.toggle("is-visible", showMoney);
  form.elements.skill.required = showSkill;
  form.elements.money.required = showMoney;
}

function syncRoleButtons() {
  roleButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.role === currentRole);
  });
}

function syncModuleButtons() {
  moduleButtons.forEach((button) => {
    button.classList.toggle("is-active", button.dataset.module === activeModule);
  });
}

function getSearchText(item) {
  return [
    item.title,
    item.organization,
    item.category,
    item.date,
    item.location,
    item.content,
    item.skill,
    item.money,
    item.benefitType
  ]
    .join(" ")
    .toLowerCase();
}

function getRecordSearchText(record) {
  const item = getRecordEvent(record);
  return [getSearchText(item), record.did, record.learned].join(" ").toLowerCase();
}

function extractKeywords(text) {
  const candidates = ["沟通", "协作", "调研", "运营", "翻译", "记录", "复盘", "数据", "引导", "执行"];
  const matches = candidates.filter((word) => text.includes(word));
  return matches.length > 0 ? matches.slice(0, 5) : ["持续参与", "主动复盘"];
}

function containsAny(text, words) {
  return words.some((word) => text.includes(word));
}

function hasMoney(item) {
  return item.benefitType === "money" || item.benefitType === "both";
}

function compareDate(left, right) {
  return new Date(left).getTime() - new Date(right).getTime();
}

function toMonthKey(value) {
  const date = new Date(value);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}`;
}

function toDateKey(value) {
  const date = new Date(value);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, "0")}-${String(date.getDate()).padStart(2, "0")}`;
}

function toDateTimeInput(value) {
  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return "";
  }

  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, "0");
  const day = String(date.getDate()).padStart(2, "0");
  const hour = String(date.getHours()).padStart(2, "0");
  const minute = String(date.getMinutes()).padStart(2, "0");
  return `${year}-${month}-${day}T${hour}:${minute}`;
}

function formatMonthLabel(value) {
  const date = new Date(value);
  return new Intl.DateTimeFormat("zh-CN", {
    year: "numeric",
    month: "long"
  }).format(date);
}

function formatTimeRange(startValue, endValue) {
  const start = new Date(startValue);
  const end = new Date(endValue);

  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    return "时间未定";
  }

  const formatter = new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit"
  });
  return `${formatter.format(start)}-${formatter.format(end)}`;
}

function formatEventDateRange(startValue, endValue) {
  const startText = formatDate(startValue);
  const start = new Date(startValue);
  const end = new Date(endValue);

  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime())) {
    return startText;
  }

  const endFormatterOptions = isSameDay(start, end)
    ? { hour: "2-digit", minute: "2-digit" }
    : { month: "2-digit", day: "2-digit", hour: "2-digit", minute: "2-digit" };

  return `${startText} - ${new Intl.DateTimeFormat("zh-CN", endFormatterOptions).format(end)}`;
}

function isValidDateRange(startValue, endValue) {
  const start = new Date(startValue);
  const end = new Date(endValue);
  return !Number.isNaN(start.getTime()) && !Number.isNaN(end.getTime()) && end > start;
}

function isSameDay(left, right) {
  return left.getFullYear() === right.getFullYear()
    && left.getMonth() === right.getMonth()
    && left.getDate() === right.getDate();
}

function getCategoryColor(index) {
  const colors = ["#16756f", "#d75d45", "#315e86", "#c38a20", "#6a5a91", "#4e6f55"];
  return colors[index % colors.length];
}

function formatDate(value) {
  if (!value) {
    return "时间未定";
  }

  const date = new Date(value);

  if (Number.isNaN(date.getTime())) {
    return "时间未定";
  }

  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    weekday: "short"
  }).format(date);
}

function createSvgElement(tagName) {
  return document.createElementNS("http://www.w3.org/2000/svg", tagName);
}

function createEventId() {
  if (globalThis.crypto?.randomUUID) {
    return globalThis.crypto.randomUUID();
  }

  return `event-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function normalize(value) {
  return String(value ?? "").trim();
}

function showMessage(text, type) {
  formMessage.textContent = text;
  formMessage.style.color = type === "error" ? "#d75d45" : "#0d514d";
}

function showChallengeMessage(text, type) {
  challengeMessage.textContent = text;
  challengeMessage.style.color = type === "error" ? "#d75d45" : "#0d514d";
}

function showScheduleMessage(text, type) {
  scheduleMessage.textContent = text;
  scheduleMessage.style.color = type === "error" ? "#d75d45" : "#0d514d";
}

function showCoachMessage(text, type) {
  coachMessage.textContent = text;
  coachMessage.style.color = type === "error" ? "#d75d45" : "#0d514d";
}

function showAuthMessage(text, type) {
  authMessage.textContent = text;
  authMessage.style.color = type === "error" ? "#d75d45" : "#0d514d";
}
