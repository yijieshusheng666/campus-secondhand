/**
 * 当前登录用户的唯一数据源。
 *
 * 以前每个组件各自 `JSON.parse(localStorage.user)` 读一份快照，个人中心改完头像/昵称后，
 * 导航栏那份快照不会更新 —— 表现就是右上角头像一直是昵称首字。
 * 现在统一走这个共享的响应式对象：谁改了都往这里写，用到它的组件立刻同步，
 * localStorage 仍然作为持久化（刷新页面、重开标签页后还在）。
 *
 * 登录令牌（token）也在这里管：它是服务端判定调用方身份的唯一依据，
 * 与 user 同生共死 —— 退出登录必须两个一起清。
 */
import { ref } from 'vue'

const KEY = 'user'
const TOKEN_KEY = 'token'

function read() {
  try {
    return JSON.parse(localStorage.getItem(KEY) || 'null')
  } catch (e) {
    return null
  }
}

export const currentUser = ref(read())

/** 登录令牌；未登录为空串。api.js 会给每个请求带上它。 */
export const token = ref(localStorage.getItem(TOKEN_KEY) || '')

export function getToken() {
  return token.value
}

/**
 * 整体替换：登录成功后写入（user + token）；传 null 表示退出登录。
 *
 * 注意退出时 token 必须一起清：只清 user 的话，本地还留着一个有效令牌，
 * 下一个人在这台机器上打开页面，请求依然会以你的身份发出去。
 */
export function setUser(user, authToken) {
  currentUser.value = user || null
  if (user) {
    localStorage.setItem(KEY, JSON.stringify(user))
    if (authToken) {
      token.value = authToken
      localStorage.setItem(TOKEN_KEY, authToken)
    }
  } else {
    localStorage.removeItem(KEY)
    token.value = ''
    localStorage.removeItem(TOKEN_KEY)
  }
}

/** 局部更新（改昵称、换头像、补 role…），改完立即写回 localStorage。 */
export function patchUser(patch) {
  // 未登录时直接返回：以前这里会凭空构造出 {…patch} 并写进 localStorage，
  // 造出一个「半登录」用户，导航栏会显示成一个没有 id 的假账号。
  if (!currentUser.value) return
  currentUser.value = { ...currentUser.value, ...patch }
  localStorage.setItem(KEY, JSON.stringify(currentUser.value))
}

/** 从 localStorage 重新读一遍（路由切换、别的标签页改过时用）。没变化就不动，避免多余重渲染。 */
export function reloadUser() {
  token.value = localStorage.getItem(TOKEN_KEY) || ''
  const next = read()
  if (JSON.stringify(next) !== JSON.stringify(currentUser.value)) {
    currentUser.value = next
  }
}
