/**
 * 当前登录用户的唯一数据源。
 *
 * 以前每个组件各自 `JSON.parse(localStorage.user)` 读一份快照，个人中心改完头像/昵称后，
 * 导航栏那份快照不会更新 —— 表现就是右上角头像一直是昵称首字。
 * 现在统一走这个共享的响应式对象：谁改了都往这里写，用到它的组件立刻同步，
 * localStorage 仍然作为持久化（刷新页面、重开标签页后还在）。
 */
import { ref } from 'vue'

const KEY = 'user'

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || 'null')
  } catch (e) {
    return null
  }
}

export const currentUser = ref(read())

/** 整体替换：登录成功后写入；传 null 表示退出登录。 */
export function setUser(user) {
  currentUser.value = user || null
  if (user) {
    localStorage.setItem(KEY, JSON.stringify(user))
  } else {
    localStorage.removeItem(KEY)
  }
}

/** 局部更新（改昵称、换头像、补 role…），改完立即写回 localStorage。 */
export function patchUser(patch) {
  currentUser.value = { ...(currentUser.value || {}), ...patch }
  localStorage.setItem(KEY, JSON.stringify(currentUser.value))
}

/** 从 localStorage 重新读一遍（路由切换、别的标签页改过时用）。没变化就不动，避免多余重渲染。 */
export function reloadUser() {
  const next = read()
  if (JSON.stringify(next) !== JSON.stringify(currentUser.value)) {
    currentUser.value = next
  }
}
