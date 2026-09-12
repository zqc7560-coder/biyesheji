<script setup>
/**
 * 后台-用户管理页（M2）—— 对接《API接口设计.md》§8 的三条治理接口：
 * GET /api/admin/users（分页）、PATCH /api/admin/users/{id}/status（启禁）、
 * POST /api/admin/users/{id}/roles（分配角色）。
 *
 * 说明：本权限点 user:manage 只有 ROLE_ADMIN 持有（M1 授权矩阵种子），
 * 因此页面内不再做角色细分；接口 403/1005 由 Axios 拦截器统一 toast，
 * 页面只处理成功态。角色选项是常量两枚（ROLE_USER/ROLE_ADMIN）：
 * 角色管理 CRUD 不在课题范围（M1 决策：角色集合为常量），故不放假想的 /api/admin/roles。
 */
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import request from '@/api/request'

/** 查询条件 + 分页（字段名对齐后端 @RequestParam：keyword/status/pageNum/pageSize） */
const query = reactive({ keyword: '', status: null, pageNum: 1, pageSize: 10 })
const pageData = ref({ total: 0, list: [] })
const loading = ref(false)

/** 角色选项：id 与 data.sql 种子显式主键一致（ROLE_USER=1 / ROLE_ADMIN=2） */
const ROLE_OPTIONS = [
  { id: 1, code: 'ROLE_USER', name: '普通用户' },
  { id: 2, code: 'ROLE_ADMIN', name: '管理员' }
]

async function load() {
  loading.value = true
  try {
    // 拦截器已拆信封：这里直接拿到 PageResult { total, pages, pageNum, pageSize, list }
    const data = await request.get('/admin/users', { params: query })
    pageData.value = data
  } finally {
    loading.value = false
  }
}
onMounted(load)

/** 查询按钮：回到第 1 页再拉（改条件后留在第 5 页是常见误操作源） */
function search() {
  query.pageNum = 1
  load()
}

/** 启禁切换：确认框里说清后果（禁用=该用户所有端立即下线），避免误点 */
async function toggleStatus(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      next === 0
        ? `禁用「${row.nickname}」将使其所有登录端立即下线，确认？`
        : `确认启用「${row.nickname}」？`,
      '账号状态', { type: 'warning' })
  } catch {
    return
  }
  await request.patch(`/admin/users/${row.id}/status`, { status: next })
  ElMessage.success(next === 0 ? '已禁用并踢出登录' : '已启用')
  load()
}

/** 角色分配对话框状态（整组覆盖式：见后端 assignRoles 注释） */
const roleDialog = reactive({ visible: false, userId: null, username: '', roleIds: [] })

function openRoleDialog(row) {
  roleDialog.userId = row.id
  roleDialog.username = row.username
  roleDialog.roleIds = [...(row.roles || [])]
    .map((code) => ROLE_OPTIONS.find((r) => r.code === code)?.id)
    .filter(Boolean)
  roleDialog.visible = true
}

async function submitRoles() {
  await request.post(`/admin/users/${roleDialog.userId}/roles`, { roleIds: roleDialog.roleIds })
  ElMessage.success('角色已更新，目标用户需重新登录')
  roleDialog.visible = false
  load()
}
</script>

<template>
  <div>
    <div style="display: flex; gap: 8px; margin-bottom: 12px">
      <el-input v-model="query.keyword" placeholder="用户名/昵称/邮箱" clearable style="width: 220px"
                @keyup.enter="search" />
      <el-select v-model="query.status" placeholder="状态" clearable style="width: 120px">
        <el-option label="正常" :value="1" />
        <el-option label="禁用" :value="0" />
      </el-select>
      <el-button type="primary" @click="search">查询</el-button>
    </div>

    <el-table :data="pageData.list" v-loading="loading" row-key="id">
      <el-table-column prop="id" label="ID" width="70" />
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="nickname" label="昵称" width="140" />
      <el-table-column prop="email" label="邮箱" min-width="180" />
      <el-table-column label="角色" width="160">
        <template #default="{ row }">
          <el-tag v-for="r in row.roles" :key="r" size="small" style="margin-right: 4px">{{ r }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'" size="small">
            {{ row.status === 1 ? '正常' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180">
        <template #default="{ row }">
          <el-button size="small" @click="openRoleDialog(row)">角色</el-button>
          <el-button size="small" :type="row.status === 1 ? 'danger' : 'success'" @click="toggleStatus(row)">
            {{ row.status === 1 ? '禁用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top: 12px" layout="total, prev, pager, next"
                   :total="Number(pageData.total)" :page-size="query.pageSize"
                   v-model:current-page="query.pageNum" @current-change="load" />

    <el-dialog v-model="roleDialog.visible" title="分配角色（整组覆盖）" width="420px">
      <p style="color: var(--el-text-color-secondary)">用户：{{ roleDialog.username }}（保存后该用户全部登录端将强制重新登录）</p>
      <el-checkbox-group v-model="roleDialog.roleIds">
        <el-checkbox v-for="r in ROLE_OPTIONS" :key="r.id" :value="r.id">{{ r.name }}（{{ r.code }}）</el-checkbox>
      </el-checkbox-group>
      <template #footer>
        <el-button @click="roleDialog.visible = false">取消</el-button>
        <el-button type="primary" :disabled="!roleDialog.roleIds.length" @click="submitRoles">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
