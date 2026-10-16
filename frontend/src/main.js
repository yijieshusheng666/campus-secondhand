import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
// 命令式 API（ElMessage / ElMessageBox / ElNotification）不走 <el-xxx> 模板标签，
// unplugin-auto-import 在 dev HMR 场景下偶发漏 inject，被 inline 渲染到当前组件里
// 之后会被父级 position:sticky / overflow 挤成一条细条（实测过）。这里显式 import +
// 挂全局 prototype 兜底，三件事一起做：
import {
  ElMessage,
  ElMessageBox,
  ElNotification,
} from 'element-plus'
import 'element-plus/theme-chalk/el-message.css'
import 'element-plus/theme-chalk/el-message-box.css'
import 'element-plus/theme-chalk/el-notification.css'

const app = createApp(App)
app.config.globalProperties.$message = ElMessage
app.config.globalProperties.$messageBox = ElMessageBox
app.config.globalProperties.$notify = ElNotification
// 浏览器全局也挂一份，避免任何外部 .js 漏掉 import
window.ElMessage = ElMessage
window.ElMessageBox = ElMessageBox
window.ElNotification = ElNotification

import imgFallback from './directives/imgFallback'

// 全局注册图片兜底指令。放在这里而不是逐个组件 import，
// 是为了「以后新增页面不用记得加，忘了就又多一处破图」。
app.directive('img-fallback', imgFallback)

app.use(router).mount('#app')
