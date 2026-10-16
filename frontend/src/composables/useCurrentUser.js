/**
 * 当前登录用户的统一访问入口。
 *
 * <p>背景：以前每个视图都自己写 {@code JSON.parse(localStorage.getItem('user') || 'null')}，
 * 5 处各自读一份快照，个人中心改完头像/昵称后，导航栏那份快照不会更新。</p>
 *
 * <p>现在统一走 {@link ./src/session.js} 里那个共享 ref：
 * - <code>currentUser</code> 是响应式的，谁引用它谁就跟着改；
 * - <code>setUser</code> 是整体替换（登录 / 退出）；
 * - <code>patchUser</code> 是局部更新（换头像、补 role）；
 * - <code>reloadUser</code> 是从 localStorage 重新读一遍（路由切换、其它标签页改过时）。</p>
 *
 * <p>这里只是包一层更易读的 API，Vue 组件原来写
 * {@code const user = JSON.parse(localStorage.getItem('user') || 'null')}
 * 改成 {@code const user = useCurrentUser()}，行为完全一致。</p>
 */
import { currentUser, patchUser, reloadUser, setUser } from '../session'

export function useCurrentUser() {
  return currentUser
}

export { setUser, patchUser, reloadUser }
