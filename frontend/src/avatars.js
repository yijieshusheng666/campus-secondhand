/**
 * 用户头像：只有内置这 4 张可以选，前端不给上传入口。
 *
 * 图片在 frontend/public/images/avatars/，已裁成圆形并将圆外做成透明，
 * 放在圆形头像框（el-avatar）或深色背景上都不会露出白角。
 * 选中的头像以 key（avatar-1..4）存到用户表的 users.avatar 字段，
 * 后端 /user/update 会用同一份白名单校验，保证「只能在这几个之间换」。
 */
const BASE = '/images/avatars'

export const AVATARS = [
  { key: 'avatar-1', url: `${BASE}/avatar-1.png`, name: '剪影' },
  { key: 'avatar-2', url: `${BASE}/avatar-2.png`, name: '女生' },
  { key: 'avatar-3', url: `${BASE}/avatar-3.png`, name: '男生' },
  { key: 'avatar-4', url: `${BASE}/avatar-4.png`, name: '毕业生' }
]

const URL_BY_KEY = Object.fromEntries(AVATARS.map(a => [a.key, a.url]))

/** 用户选过的头像地址；没选过（或后端返回了不认识的值）返回空串，由界面回退成昵称首字。 */
export function avatarUrl(user) {
  return (user && URL_BY_KEY[user.avatar]) || ''
}
