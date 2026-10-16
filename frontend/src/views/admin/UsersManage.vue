<script setup>
/**
 * 用户管理
 *
 * <p>数据源 GET /api/admin/users（AdminQueryController）。那个接口是白名单组装的，
 * **不返回 password** —— 这里不要试图在前端加任何"看密码"入口。</p>
 *
 * <p>只读。刻意没有"改角色""重置密码"按钮：这个项目还没有服务端鉴权，
 * 放开这两个操作等于任何人都能把普通用户提成 admin。真要做必须先补
 * 服务端身份校验 + 操作审计。</p>
 */
import { ref, computed, onMounted } from 'vue'
import request from '../../api'
import { avatarUrl } from '../../avatars'

const rows = ref([])
const loading = ref(false)
const kw = ref('')
const role = ref('')
const verified = ref('')
const detailVisible = ref(false)
const detailRow = ref(null)

async function load() {
  loading.value = true
  try {
    const params = {}
    if (kw.value.trim()) params.kw = kw.value.trim()
    if (role.value) params.role = role.value
    if (verified.value !== '') params.verified = verified.value
    const { data } = await request.get('/admin/users', { params })
    rows.value = data.data || []
  } catch (e) {
    rows.value = []
  } finally {
    loading.value = false
  }
}

const stats = computed(() => {
  const total = rows.value.length
  const admins = rows.value.filter(r => r.role === 'admin').length
  const verifiedCount = rows.value.filter(r => r.verified).length
  return { total, admins, verified: verifiedCount }
})

// 见 GoodsManage 的 preview()：MessageBox 不处理换行，长文本会塌成一段并触发抖动，
// 结构化字段用 el-descriptions 呈现更稳也更好读。
function detail(row) {
  detailRow.value = row
  detailVisible.value = true
}

function fmt(t) {
  return t ? String(t).replace('T', ' ').slice(0, 16) : '—'
}

function reset() {
  kw.value = ''; role.value = ''; verified.value = ''
  load()
}

onMounted(load)
</script>

<template>
  <div>
    <div class="stat-row">
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ stats.total }}</div>
        <div class="stat-label">当前结果</div>
        <div class="stat-hint">按筛选条件统计</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ stats.verified }}</div>
        <div class="stat-label">已实名</div>
        <div class="stat-hint">{{ stats.total ? Math.round(stats.verified / stats.total * 100) : 0 }}%</div>
      </el-card>
      <el-card shadow="never" class="stat-card">
        <div class="stat-value">{{ stats.admins }}</div>
        <div class="stat-label">管理员</div>
        <div class="stat-hint">role = admin</div>
      </el-card>
    </div>

    <el-alert type="info" show-icon :closable="false" class="mb"
      title="本页只读"
      description="平台暂未提供角色变更与密码重置接口：当前项目没有服务端鉴权，放开这两个操作等于任何人都能把自己提成管理员。"
    />

    <el-card shadow="never">
      <div class="toolbar">
        <el-input v-model="kw" placeholder="搜索昵称 / 学号" clearable style="width: 200px"
                  @keyup.enter="load" @clear="load" />
        <el-select v-model="role" placeholder="全部角色" clearable style="width: 130px" @change="load">
          <el-option label="普通用户" value="user" />
          <el-option label="管理员" value="admin" />
        </el-select>
        <el-select v-model="verified" placeholder="实名状态" clearable style="width: 130px" @change="load">
          <el-option label="已实名" :value="true" />
          <el-option label="未实名" :value="false" />
        </el-select>
        <el-button @click="load">查询</el-button>
        <el-button text @click="reset">重置</el-button>
        <span class="grow" />
        <span class="count">共 {{ rows.length }} 条</span>
      </div>

      <div class="table-wrap">
        <el-table :data="rows" v-loading="loading" size="small" empty-text="没有匹配的用户">
          <el-table-column label="用户" min-width="180">
            <template #default="{ row }">
              <div class="u-cell">
                <el-avatar :size="30" class="u-avatar" :src="avatarUrl(row)">
                  {{ (row.username || '?').charAt(0) }}
                </el-avatar>
                <div>
                  <div class="u-name">{{ row.username }}</div>
                  <div class="u-stuid">学号 {{ row.stuid || '—' }}</div>
                </div>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="实名" width="130">
            <template #default="{ row }">
              <el-tag size="small" :type="row.verified ? 'success' : 'info'">
                {{ row.verified ? '已实名' : '未实名' }}
              </el-tag>
              <span v-if="row.realNameMasked" class="mask">{{ row.realNameMasked }}</span>
            </template>
          </el-table-column>
          <el-table-column label="角色" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="row.role === 'admin' ? 'danger' : 'info'">
                {{ row.role === 'admin' ? '管理员' : '普通用户' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="注册时间" width="140">
            <template #default="{ row }">{{ fmt(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="90" align="right" fixed="right">
            <template #default="{ row }">
              <el-button size="small" text @click="detail(row)">详情</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>

    <el-dialog v-model="detailVisible" :title="`用户 #${detailRow?.id ?? ''}`" width="480px">
      <el-descriptions v-if="detailRow" :column="1" border size="small">
        <el-descriptions-item label="昵称">{{ detailRow.username }}</el-descriptions-item>
        <el-descriptions-item label="学号">{{ detailRow.stuid || '—' }}</el-descriptions-item>
        <el-descriptions-item label="实名">
          <el-tag size="small" :type="detailRow.verified ? 'success' : 'info'">
            {{ detailRow.verified ? '已实名' : '未实名' }}
          </el-tag>
          <span v-if="detailRow.realNameMasked" class="mask">{{ detailRow.realNameMasked }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="角色">
          {{ detailRow.role === 'admin' ? '管理员' : '普通用户' }}
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">{{ fmt(detailRow.createdAt) }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
      </template>
    </el-dialog>
</template>

<style scoped>
.stat-row { display: flex; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.stat-card { flex: 1; min-width: 150px; }
.stat-value { font-size: 26px; font-weight: 700; color: var(--ink-color); line-height: 1.2; }
.stat-label { font-size: 13px; margin-top: 4px; color: #666; }
.stat-hint { font-size: 11px; margin-top: 2px; color: #bbb; }
.mb { margin-bottom: 12px; }
.toolbar { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.grow { flex: 1; }
.count { font-size: 12px; color: #999; }
.table-wrap { overflow-x: auto; }
.u-cell { display: flex; align-items: center; gap: 9px; }
.u-avatar { background: #f2f3f5; flex-shrink: 0; }
.u-name { font-size: 13px; color: var(--ink-color); font-weight: 500; }
.u-stuid { font-size: 11px; color: #aaa; }
.mask { font-size: 11px; color: #999; margin-left: 6px; }
</style>
