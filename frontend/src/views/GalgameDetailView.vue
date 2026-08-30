<template>
  <div class="page">
    <div class="gal-detail-center">
      <!-- 加载中骨架屏 -->
      <div v-if="loading" class="gal-detail-skel">
        <el-skeleton :rows="8" animated />
      </div>

      <!-- 404：作品不存在 -->
      <div v-else-if="notFound" class="gal-detail-empty">
        <p class="gal-detail-empty-text">Galgame 不存在</p>
        <el-button type="primary" @click="goBack">返回列表</el-button>
      </div>

      <!-- 加载失败 -->
      <el-alert
        v-else-if="loadError"
        :title="loadError"
        type="error"
        :closable="false"
        class="gal-detail-error"
      />

      <!-- 详情（展示 / 编辑） -->
      <template v-else-if="detail">
        <!-- 展示模式（默认） -->
        <template v-if="!editMode">
          <div class="gal-detail-head">
            <el-button link type="primary" @click="goBack">← 返回</el-button>
            <div v-if="canEdit" class="gal-detail-actions">
              <el-button v-if="isAdmin && isPending" type="success" plain size="small" @click="approve">通过审核</el-button>
              <el-button v-if="isAdmin && isPending" type="warning" plain size="small" @click="reject">拒绝</el-button>
              <el-button type="primary" plain size="small" @click="enterEdit">编辑</el-button>
              <el-button v-if="canDelete" type="danger" plain size="small" @click="remove">删除</el-button>
            </div>
          </div>

          <div class="gal-detail-card">
            <div class="gal-detail-cover">
              <img
                v-if="detail.image && !detail._coverError"
                :src="resolveAssetUrl(detail.image)"
                :alt="detail.name"
                @error="coverImgError"
              />
              <div v-else class="gal-detail-cover-placeholder">无封面</div>
            </div>
            <div class="gal-detail-body">
              <div class="gal-detail-name-row">
                <h1 class="gal-detail-name">{{ detail.name }}</h1>
                <el-tag v-if="detail.status === 'pending'" type="warning" size="small">待审核</el-tag>
                <el-tag v-else-if="detail.status === 'rejected'" type="danger" size="small">已拒绝</el-tag>
              </div>
              <el-alert
                v-if="detail.status === 'rejected' && detail.reject_reason"
                :title="`拒绝理由：${detail.reject_reason}`"
                type="error"
                :closable="false"
                class="gal-reject-reason"
              />
              <p v-if="detail.staff" class="gal-detail-staff">
                会社：<a
                  v-if="detail.company_id"
                  class="gal-staff-link"
                  @click="goCompany(detail.company_id)"
                >{{ detail.staff }}</a>
                <template v-else>{{ detail.staff }}</template>
              </p>
              <p v-if="detail.description" class="gal-detail-desc">{{ detail.description }}</p>
              <div class="gal-links">
                <template v-if="detail.links && detail.links.length">
                  <button
                    v-for="(link, i) in detail.links"
                    :key="i"
                    class="gal-link"
                    @click="openLink(link.url)"
                  >{{ link.label || link.url }}</button>
                </template>
                <span v-else class="gal-no-link">暂无资源链接</span>
              </div>
              <div class="gal-detail-meta">
                <span v-if="detail.creator">提交人：{{ detail.creator }}</span>
                <span v-if="detail.release_date">发售：{{ detail.release_date }}</span>
                <span v-if="detail.created_at">创建：{{ formatTime(detail.created_at) }}</span>
                <span v-if="detail.updated_at">更新：{{ formatTime(detail.updated_at) }}</span>
              </div>
              <!-- 评分高亮块：大号金色评分 + 人数（无评分显示暂无） -->
              <div class="gal-big-rating">
                <template v-if="detail.rating_avg != null">
                  <span class="gal-big-score">{{ Number(detail.rating_avg).toFixed(2) }}</span>
                  <span class="gal-big-denom">/ 10</span>
                  <span v-if="detail.rating_count > 0" class="gal-big-count">★ {{ detail.rating_count }} 人评分</span>
                  <span v-else class="gal-big-count">暂无评分人数</span>
                </template>
                <span v-else class="gal-big-score gal-big-none">暂无评分</span>
              </div>
              <!-- 分类（旧系统 section_key → 中文小 tag，纯展示不可点）；无分类不渲染 -->
              <div v-if="(detail.categories || []).length" class="gal-cat-row">
                <span class="gal-cat-label">分类：</span>
                <span v-for="c in detail.categories" :key="c" class="gal-tag">{{ categoryLabel(c) }}</span>
              </div>
              <!-- 资源 chips：类型 / 语言 / 平台（type 蓝 / language 灰 / platform 绿），点击进标签页 -->
              <div v-if="resourceTags.length" class="gal-resource-chips">
                <span
                  v-for="t in resourceTags"
                  :key="t.id"
                  class="gal-resource-chip"
                  :class="`res-${t.category}`"
                  @click="goTag(t.id)"
                >{{ t.name }}</span>
              </div>
              <!-- 标签区（KUNGal Tag 风格）：类别筛选 + 剧透开关 + chip 网格 -->
              <div class="gal-tag-section">
                <h3 class="gal-tag-section-title">标签</h3>
                <div class="gal-tag-filter-row">
                  <button
                    v-for="f in TAG_SECTION_FILTERS"
                    :key="f.key"
                    class="gal-tag-filter-chip"
                    :class="{ active: tagSectionFilter === f.key }"
                    type="button"
                    @click="tagSectionFilter = f.key"
                  >{{ f.label }}</button>
                  <label class="gal-spoiler-toggle">
                    <el-checkbox v-model="showSpoiler" size="small">显示剧透标签</el-checkbox>
                  </label>
                </div>
                <div v-if="filteredTagSectionTags.length" class="gal-tag-grid">
                  <span
                    v-for="t in filteredTagSectionTags"
                    :key="t.id"
                    class="gal-tag-chip"
                    :class="`cat-${t.category}`"
                    @click="goTag(t.id)"
                  >
                    {{ t.name }}<span v-if="t.galgame_count" class="gal-tag-count">+{{ t.galgame_count }}</span>
                    <span v-if="t.spoiler_level > 0" class="gal-tag-spoiler" :class="`lvl-${t.spoiler_level}`">{{ t.spoiler_level === 2 ? '严重剧透' : '剧透' }}</span>
                  </span>
                </div>
                <p v-else class="gal-tag-empty">暂无标签</p>
              </div>
              <div class="gal-rate-row">
                <template v-if="detail.status === 'approved'">
                  <el-rate
                    v-model="myScore"
                    :max="10"
                    allow-half
                    show-score
                    score-template="{value} 分"
                    :disabled="!!detail.rated"
                  />
                  <el-button
                    v-if="!detail.rated"
                    type="primary"
                    plain
                    size="small"
                    @click="submitRating"
                  >提交评分</el-button>
                  <span v-else class="gal-rated-hint">你已评过分</span>
                </template>
                <span v-else class="gal-rated-hint">审核通过后可评分</span>
              </div>
              <div class="gal-contributors-link" @click="goContributors">条目贡献者</div>
            </div>
          </div>

          <!-- 制作人员 / 角色 / 评论 / 画廊 同级切换 -->
          <div class="gal-section-tabs">
            <button
              v-for="t in sectionTabs"
              :key="t.key"
              class="gal-section-tab"
              :class="{ active: sectionTab === t.key }"
              type="button"
              @click="sectionTab = t.key"
            >{{ t.label }}</button>
          </div>

          <!-- 制作人员：同职业合并为一行「职业 人员1 人员2」，超长自动换行 -->
          <div v-if="sectionTab === 'staffs'" class="gal-section-pane">
            <div v-if="detail.staffs && detail.staffs.length" class="gal-relation-list">
              <div v-for="g in staffGroups" :key="g.description || '__none__'" class="gal-relation-item">
                <span v-if="g.description" class="gal-relation-desc">{{ g.description }}</span>
                <span class="gal-staff-group-members">
                  <a
                    v-for="s in g.members"
                    :key="s.id"
                    class="gal-staff-link"
                    @click="goStaff(s.id)"
                  >{{ s.name }}</a>
                </span>
              </div>
            </div>
            <p v-else class="gal-section-empty">暂无制作人员</p>
          </div>

          <!-- 角色 -->
          <div v-if="sectionTab === 'characters'" class="gal-section-pane">
            <div v-if="detail.characters && detail.characters.length" class="gal-char-grid">
              <div v-for="c in detail.characters" :key="c.id" class="gal-relation-item gal-char-card">
                <div class="gal-char-cover">
                  <img
                    v-if="c.image && !c._coverError"
                    :src="resolveAssetUrl(c.image)"
                    :alt="c.name"
                    loading="lazy"
                    @error="charCoverError(c)"
                  />
                  <div v-else class="gal-char-cover-placeholder">角色</div>
                </div>
                <div class="gal-char-info">
                  <a class="gal-staff-link" @click="goCharacter(c.id)">{{ c.name }}</a>
                  <span v-if="c.description" class="gal-relation-desc">{{ c.description }}</span>
                </div>
              </div>
            </div>
            <p v-else class="gal-section-empty">暂无角色</p>
          </div>

          <!-- 评论 -->
          <template v-if="sectionTab === 'replies' && detail.status === 'approved'">
            <el-card class="replies-card">
              <template #header>
                <span class="replies-title">
                  评论 ({{ detail.replies?.length || 0 }})
                </span>
              </template>

              <div class="reply-view-toggle">
                <el-radio-group v-model="viewType" size="small">
                  <el-radio-button label="short">短评 ({{ shortReplies.length }})</el-radio-button>
                  <el-radio-button label="long">长评 ({{ longReplies.length }})</el-radio-button>
                </el-radio-group>
              </div>

              <div v-if="!filteredReplies.length" class="reply-empty">
                {{ viewType === 'long' ? '还没有长评，快来写一篇吧。' : '还没有短评，来抢沙发吧。' }}
              </div>
              <div v-else class="reply-list">
                <div v-for="r in filteredReplies" :key="r.id" class="reply">
                  <div class="reply-top">
                    <span class="reply-author">
                      <router-link v-if="r.user_id" :to="`/user/${r.user_id}`" class="author-link">
                        {{ r.author }}
                      </router-link>
                      <span v-else>{{ r.author }}</span>
                    </span>
                    <span v-if="r.rating != null" class="reply-rating" title="该用户对该游戏评分">★ {{ r.rating }}</span>
                    <span class="dot">·</span>
                    <span class="time">{{ formatTime(r.created_at) }}</span>
                  </div>
                  <div v-if="parentNameOf(r)" class="reply-parent">回复 @{{ parentNameOf(r) }}</div>
                  <p class="reply-content">{{ r.content }}</p>
                  <div v-if="r.images && r.images.length" class="reply-images">
                    <img v-for="u in r.images" :key="u" :src="resolveAssetUrl(u)" alt="评论图片" @click="openImage(u)" />
                  </div>
                  <div class="reply-actions">
                    <button
                      class="like-button small"
                      :class="{ liked: r.liked }"
                      type="button"
                      :disabled="replyLiking.has(r.id)"
                      @click="toggleReplyLike(r)"
                    >
                      {{ r.liked ? '♥' : '♡' }} {{ r.like_count }}
                    </button>
                    <button
                      class="like-button small dislike"
                      :class="{ disliked: r.disliked }"
                      type="button"
                      :disabled="replyDisliking.has(r.id)"
                      @click="toggleReplyDislike(r)"
                    >
                      {{ r.disliked ? '▼' : '▽' }} {{ r.dislike_count ?? 0 }}
                    </button>
                    <el-button link size="small" @click="replyingTo = r">回复</el-button>
                    <el-button
                      v-if="!(user && Number(r.user_id) === Number(user.id))"
                      link
                      size="small"
                      @click="openReport(r)"
                    >举报</el-button>
                    <el-button
                      v-if="user && Number(r.user_id) === Number(user.id)"
                      link
                      type="danger"
                      size="small"
                      class="reply-delete-button"
                      @click="deleteReply(r)"
                    >
                      删除
                    </el-button>
                  </div>
                </div>
              </div>

              <el-divider />

              <div class="reply-form">
                <template v-if="!loggedIn">
                  <div class="login-prompt">
                    <p>登录后即可评论。</p>
                    <el-button type="primary" @click="goLogin">去登录</el-button>
                  </div>
                </template>
                <template v-else>
                  <div v-if="replyingTo" class="replying-to">
                    <span class="replying-label">回复 @{{ replyingTo.author }}</span>
                    <el-button link size="small" @click="replyingTo = null">取消</el-button>
                  </div>
                  <div class="reply-type-toggle">
                    <el-radio-group v-model="replyType" size="small">
                      <el-radio-button label="short">短评</el-radio-button>
                      <el-radio-button label="long">长评</el-radio-button>
                    </el-radio-group>
                  </div>
                  <MentionTextarea
                    v-model="replyForm.content"
                    :rows="4"
                    :maxlength="2000"
                    show-word-limit
                    :placeholder="replyType === 'long' ? '写下你的长评' : '写下你的短评'"
                  />
                  <div v-if="replyForm.images.length" class="reply-image-previews">
                    <div v-for="(u, i) in replyForm.images" :key="u" class="reply-image-preview">
                      <img :src="resolveAssetUrl(u)" alt="评论图片预览" />
                      <button type="button" aria-label="移除图片" @click="replyForm.images.splice(i, 1)">×</button>
                    </div>
                  </div>
                  <EmojiPicker
                    v-if="replyEmojiVisible"
                    class="reply-emoji-picker"
                    @pick="onReplyEmoji"
                  />
                  <div class="reply-form-actions">
                    <el-button
                      class="emoji-toggle"
                      :class="{ active: replyEmojiVisible }"
                      type="text"
                      @click="replyEmojiVisible = !replyEmojiVisible"
                    >😀</el-button>
                    <input
                      ref="replyImageInput"
                      type="file"
                      accept="image/*"
                      multiple
                      style="display:none"
                      @change="onReplyImagesChange"
                    />
                    <el-button
                      class="image-toggle"
                      type="text"
                      :disabled="replyImageUploading"
                      @click="replyImageInput?.click()"
                    >🖼️</el-button>
                    <el-button type="primary" :loading="replySubmitting" @click="submitReply">
                      发表评论
                    </el-button>
                    <span class="form-status" :class="{ error: replyStatusError }">{{ replyStatus }}</span>
                  </div>
                </template>
              </div>
            </el-card>
          </template>
          <div v-else-if="sectionTab === 'replies'" class="gal-rated-hint gal-comment-hint">审核通过后可评论</div>

          <!-- 画廊（多图上传 / 删除 / 展示；上传与删除仅 canEdit 可见） -->
          <div v-if="sectionTab === 'gallery'" class="gal-section-pane">
            <div v-if="(detail.gallery || []).length" class="gal-gallery-grid">
              <div v-for="img in detail.gallery" :key="img.id" class="gal-gallery-item">
                <img
                  :src="resolveAssetUrl(img.url)"
                  :alt="`画廊图片 ${img.id}`"
                  @click="openGalleryImage(img.url)"
                />
                <el-button
                  v-if="canEditContent"
                  link
                  type="danger"
                  size="small"
                  class="gal-gallery-del"
                  @click="deleteGalleryImage(img.id)"
                >删除</el-button>
              </div>
            </div>
            <p v-else class="gal-section-empty">暂无画廊图片</p>
            <div v-if="canEditContent" class="gal-gallery-upload">
              <el-button
                type="primary"
                plain
                size="small"
                :loading="galleryUploading"
                @click="galleryFileInput?.click()"
              >上传图片</el-button>
              <input
                ref="galleryFileInput"
                type="file"
                accept="image/*"
                multiple
                style="display:none"
                @change="onGalleryFilesChange"
              />
            </div>
          </div>

          <!-- 相关系列（页面最底部，展示模式下始终显示，点卡片跳对应 galgame 详情） -->
          <div class="gal-related-section">
            <h3 class="gal-related-title">相关系列</h3>
            <div v-if="(detail.related_games || []).length" class="gal-related-grid">
              <div
                v-for="g in detail.related_games"
                :key="g.id"
                class="gal-related-card"
                @click="router.push('/galgame/' + g.id)"
              >
                <div class="gal-related-cover">
                  <img v-if="g.image" :src="resolveAssetUrl(g.image)" :alt="g.name" />
                  <span v-else class="gal-related-placeholder">无封面</span>
                </div>
                <span class="gal-related-name">{{ g.name }}</span>
              </div>
            </div>
            <p v-else class="gal-section-empty">暂无相关系列</p>
          </div>

          <el-dialog
            v-model="reportDialogVisible"
            title="举报"
            width="440px"
            :close-on-click-modal="false"
            @closed="reportReason = ''"
          >
            <el-input
              v-model="reportReason"
              type="textarea"
              :rows="3"
              maxlength="200"
              show-word-limit
              placeholder="请说明举报原因（可选），如广告、辱骂、违规内容等"
            />
            <template #footer>
              <el-button @click="reportDialogVisible = false">取消</el-button>
              <el-button type="danger" :loading="reportSubmitting" @click="submitReport">提交举报</el-button>
            </template>
          </el-dialog>
        </template>

        <!-- 编辑模式（管理员，字段与 AddGalgameView 完全一致） -->
        <div v-else class="add-card">
          <el-form :model="form" label-width="100px" class="add-form">
            <el-form-item label="名称" required>
              <el-input
                v-model="form.name"
                placeholder="Galgame 名称"
                maxlength="200"
                show-word-limit
              />
            </el-form-item>

            <el-form-item label="分类">
              <el-select v-model="form.categories" multiple filterable placeholder="选择分类" style="width:100%">
                <el-option-group v-for="g in CATEGORY_GROUPS" :key="g.key" :label="g.name">
                  <el-option v-for="c in GALGAME_CATEGORIES.filter(x => x.group === g.key)" :key="c.key" :value="c.key" :label="c.label" />
                </el-option-group>
              </el-select>
            </el-form-item>

            <el-form-item label="标签" required>
              <GalgameTagSelect v-model="form.tagIds" :preload-options="detailTagObjects" />
            </el-form-item>

            <el-form-item label="封面图片">
              <div class="add-cover">
                <el-upload
                  :show-file-list="false"
                  :http-request="uploadImage"
                  accept="image/*"
                >
                  <el-button type="primary" plain>选择封面图片</el-button>
                </el-upload>
                <div v-if="imagePreview" class="add-cover-preview">
                  <img :src="imagePreview" alt="封面预览" />
                  <el-button link type="danger" @click="removeCover">移除</el-button>
                </div>
              </div>
              <div class="add-hint">支持 jpg / png / gif / webp，上传后作为封面展示</div>
            </el-form-item>

            <el-form-item label="简介">
              <el-input
                v-model="form.description"
                type="textarea"
                :rows="5"
                placeholder="这部作品讲什么…"
                maxlength="2000"
                show-word-limit
              />
            </el-form-item>

            <el-form-item label="会社">
              <el-select
                v-model="form.companyId"
                clearable
                filterable
                placeholder="选择会社（可不选）"
                class="add-tags-select"
              >
                <el-option v-for="c in companyOptions" :key="c.id" :label="c.name" :value="c.id" />
              </el-select>
            </el-form-item>

            <el-form-item label="制作人员">
              <div class="gal-link-entry">
                <el-button type="primary" plain @click="openEditor('staffs')">修改制作人员</el-button>
                <span v-if="staffCount" class="gal-link-count">已选 {{ staffCount }} 名</span>
              </div>
            </el-form-item>

            <el-form-item label="角色">
              <div class="gal-link-entry">
                <el-button type="primary" plain @click="openEditor('characters')">修改角色</el-button>
                <span v-if="charCount" class="gal-link-count">已选 {{ charCount }} 名</span>
              </div>
            </el-form-item>

            <el-form-item label="发售日期">
              <el-date-picker
                v-model="form.releaseDate"
                type="date"
                value-format="YYYY-MM-DD"
                placeholder="选择发售日期"
                class="add-date-picker"
              />
            </el-form-item>

            <el-form-item label="资源链接">
              <div class="add-links">
                <div v-for="(link, i) in links" :key="i" class="add-link-row">
                  <el-input
                    v-model="link.label"
                    placeholder="标签（如「官网」）"
                    class="add-link-label"
                    maxlength="30"
                  />
                  <el-input
                    v-model="link.url"
                    placeholder="https://…"
                    class="add-link-url"
                  />
                  <el-button
                    link
                    type="danger"
                    :aria-label="`删除链接 ${i + 1}`"
                    @click="removeLink(i)"
                  >删除</el-button>
                </div>
                <el-button type="primary" plain size="small" class="add-link-btn" @click="addLink">+ 添加链接</el-button>
              </div>
            </el-form-item>

            <el-form-item label="相关系列">
              <el-select
                v-model="form.relatedIds"
                multiple
                filterable
                remote
                :remote-method="searchRelatedGames"
                :loading="relatedLoading"
                placeholder="搜索并选择关联的 galgame"
                class="gal-related-select"
              >
                <el-option v-for="g in relatedOptions" :key="g.id" :value="g.id" :label="g.name" />
              </el-select>
            </el-form-item>

            <el-form-item v-if="canEditContent" label="画廊">
              <div class="gal-link-entry">
                <el-button type="primary" plain @click="galleryEditorVisible = true">编辑画廊</el-button>
                <span v-if="galleryCount" class="gal-link-count">共 {{ galleryCount }} 张</span>
              </div>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="submit">保存</el-button>
              <el-button @click="cancelEdit">取消</el-button>
            </el-form-item>
          </el-form>

          <!-- 画廊编辑弹窗：查看 / 上传 / 删除画廊图片（写操作即时生效，关闭不丢主表单） -->
          <el-dialog v-model="galleryEditorVisible" title="编辑画廊" width="660px" :close-on-click-modal="false">
            <div v-if="(detail.gallery || []).length" class="gal-gallery-grid">
              <div v-for="img in detail.gallery" :key="img.id" class="gal-gallery-item">
                <img
                  :src="resolveAssetUrl(img.url)"
                  :alt="`画廊图片 ${img.id}`"
                  @click="openGalleryImage(img.url)"
                />
                <el-button
                  link
                  type="danger"
                  size="small"
                  class="gal-gallery-del"
                  @click="deleteGalleryImage(img.id)"
                >删除</el-button>
              </div>
            </div>
            <p v-else class="gal-section-empty">暂无画廊图片，点击下方按钮上传</p>
            <div class="gal-gallery-upload">
              <el-button
                type="primary"
                plain
                :loading="galleryUploading"
                @click="galleryEditFileInput?.click()"
              >上传图片</el-button>
              <input
                ref="galleryEditFileInput"
                type="file"
                accept="image/*"
                multiple
                style="display:none"
                @change="onGalleryFilesChange"
              />
            </div>
            <template #footer>
              <el-button type="primary" @click="galleryEditorVisible = false">完成</el-button>
            </template>
          </el-dialog>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { token, user, requireLogin } from '../store/user'
import { refreshMoe } from '../utils/moeGain'
import { getErrorMessage, resolveAssetUrl, formatTime } from '../utils/format'
import { saveDraft, loadDraft, clearDraft } from '../utils/galLinksDraft'
import MentionTextarea from '../components/MentionTextarea.vue'
import EmojiPicker from '../components/EmojiPicker.vue'
import GalgameTagSelect from '../components/GalgameTagSelect.vue'
import { TAG_SECTION_FILTERS, RESOURCE_CATEGORIES, TAG_SECTION_CATEGORIES } from '../constants/galgameTag'
import { GALGAME_CATEGORIES, CATEGORY_GROUPS, categoryLabel } from '../constants/galgameCategory'

const route = useRoute()
const router = useRouter()

const galgameId = route.params.id

// 管理员（admin_level > 0）才显示编辑/删除/审核；等级来自 store，认证后实时刷新
const isAdmin = computed(() => Number(user.value?.admin_level) > 0)

// 是否本条目的创建者（后端 galgames.created_by，数字 id）
const isCreator = computed(() => detail.value?.created_by != null && detail.value.created_by === Number(user.value?.id))

// 是否待审核状态
const isPending = computed(() => detail.value?.status === 'pending')

// 可编辑（编辑申请入口）：管理员，或创建者本人——创建者不管条目状态都能进入编辑，提交已上架条目的修改会生成修改申请待审核
const canEdit = computed(() => isAdmin.value || isCreator.value || (user.value?.id != null && detail.value?.status === 'approved'))

// 内容写操作（画廊上传/删除等）：管理员随时可做；创建者仅 pending/rejected 可改自己提交，
// approved 的创建者提交的是修改申请（后端对画廊写操作仍是 403），因此不放开
const canEditContent = computed(() => isAdmin.value || (isCreator.value && detail.value?.status !== 'approved'))

// 可删除：管理员任意删，但 pending 审核中不显示删除（用通过/拒绝处置，避免审核界面出现删除）；
// 普通创建者仅 pending 可删自己提交
const canDelete = computed(() =>
  (isAdmin.value && detail.value?.status !== 'pending') ||
  (isCreator.value && detail.value?.status === 'pending'))

const detail = ref(null)
const loading = ref(false)
const notFound = ref(false)
const loadError = ref('')
const editMode = ref(false)

// 详情页展示模式 tab：角色 / 制作人员 / 评论 / 画廊 同级切换（默认角色）
const sectionTab = ref('characters')
const sectionTabs = [
  { key: 'characters', label: '角色' },
  { key: 'staffs', label: '制作人员' },
  { key: 'replies', label: '评论' },
  { key: 'gallery', label: '画廊' },
]

const myScore = ref(0)

// ---- 评论区（仅 detail.status === 'approved' 显示，逻辑同 PostView 评论区，去掉置顶）----
const loggedIn = computed(() => !!token.value)

const replyForm = reactive({ content: '', images: [] })
const replySubmitting = ref(false)
const replyStatus = ref('')
const replyStatusError = ref(false)

// 评论类型（短评/长评）：提交时按类型给 body.is_long，长评前端拦截 <300 字
const replyType = ref('short')

// 评论列表查看 tab：短评 / 长评（已发布评论按 is_long 分流展示，默认看短评）
const viewType = ref('short')
const shortReplies = computed(() => (detail.value?.replies || []).filter((r) => !r.is_long))
const longReplies = computed(() => (detail.value?.replies || []).filter((r) => r.is_long))
const filteredReplies = computed(() => (viewType.value === 'long' ? longReplies.value : shortReplies.value))
// 制作人员按「职业」分组：同职业合并为一行「职业 人员1 人员2」，超长自动换行（保持首次出现顺序）
const staffGroups = computed(() => {
  const groups = []
  const idx = new Map()
  for (const s of detail.value?.staffs || []) {
    const key = s.description || ''
    let gi = idx.get(key)
    if (gi === undefined) {
      gi = groups.length
      idx.set(key, gi)
      groups.push({ description: key, members: [] })
    }
    groups[gi].members.push(s)
  }
  return groups
})

// 评论图片上传：隐藏 file input + 是否上传中
const replyImageInput = ref(null)
const replyImageUploading = ref(false)

// 逐张上传所选图片 → URL 存入 replyForm.images（成功后清空 input 以便重复选择）
async function onReplyImagesChange(e) {
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  replyImageUploading.value = true
  try {
    for (const file of files) {
      const fd = new FormData()
      fd.append('file', file)
      try {
        const { data } = await api.post('/comment-images', fd)
        if (data?.url) replyForm.images.push(data.url)
      } catch (err) {
        ElMessage.error(getErrorMessage(err, '图片上传失败'))
      }
    }
  } finally {
    e.target.value = ''
    replyImageUploading.value = false
  }
}

// 点击评论图片 → 新窗口打开
function openImage(u) {
  if (u) window.open(resolveAssetUrl(u), '_blank')
}

// ---- 画廊（详情页 tab，多图上传 / 删除 / 展示）----
// 隐藏 file input + 是否上传中（模式同评论图片上传）
const galleryFileInput = ref(null)
const galleryUploading = ref(false)

// 编辑模式「编辑画廊」弹窗：独立 file input（与详情页 tab 的 galleryFileInput 分离，避免同名 ref 互覆盖），change 共用 onGalleryFilesChange
const galleryEditorVisible = ref(false)
const galleryEditFileInput = ref(null)
const galleryCount = computed(() => (detail.value?.gallery || []).length)

// 逐张上传所选图片 → POST /galgames/{id}/images（multipart 字段名 files），全部成功后刷新详情
async function onGalleryFilesChange(e) {
  const files = Array.from(e.target.files || [])
  if (!files.length) return
  galleryUploading.value = true
  try {
    for (const file of files) {
      const fd = new FormData()
      fd.append('files', file)
      try {
        await api.post(`/galgames/${detail.value.id}/images`, fd)
      } catch (err) {
        ElMessage.error(getErrorMessage(err, '图片上传失败'))
      }
    }
    ElMessage.success('上传成功')
    await loadDetail({ silent: true })
  } finally {
    // 清空 input 值，保证同一文件可重复选择
    e.target.value = ''
    galleryUploading.value = false
  }
}

// 删除画廊图片：二次确认后 DELETE /galgame-images/{id}，成功刷新详情
async function deleteGalleryImage(id) {
  try {
    await ElMessageBox.confirm('确定删除这张图片吗？', '删除确认', { type: 'warning' })
  } catch {
    return // 用户取消
  }
  try {
    await api.delete(`/galgame-images/${id}`)
    ElMessage.success('删除成功')
    await loadDetail({ silent: true })
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除失败'))
  }
}

// 点击画廊图片 → 新窗口打开大图
function openGalleryImage(url) {
  if (url) window.open(resolveAssetUrl(url), '_blank')
}

// ---- 相关系列（编辑表单远程搜索多选 + 详情页底部展示框）----
const relatedOptions = ref([])
const relatedLoading = ref(false)

// 远程搜索已上架 galgame：GET /api/galgames?q= → { id, name }[] 候选
async function searchRelatedGames(q) {
  relatedLoading.value = true
  try {
    const { data } = await api.get('/galgames', {
      params: { q: (q || '').trim() || undefined },
    })
    relatedOptions.value = (Array.isArray(data) ? data : []).map((g) => ({ id: g.id, name: g.name }))
  } catch (e) {
    // 搜索失败不阻断编辑（可空）
  } finally {
    relatedLoading.value = false
  }
}

// 正在回复的目标评论对象（null 表示普通评论）
const replyingTo = ref(null)

const replyLiking = reactive(new Set())
const replyDisliking = reactive(new Set())

// 评论输入框 emoji 面板显隐
const replyEmojiVisible = ref(false)

// 点选 emoji 直接追加到评论正文末尾（面板保持展开，可连续插入）
function onReplyEmoji(e) {
  replyForm.content += e
}

// 举报弹窗状态
const reportDialogVisible = ref(false)
const reportSubmitting = ref(false)
const reportTarget = ref(null)
const reportReason = ref('')

// 编辑表单（与 AddGalgameView 完全一致）
const form = reactive({
  name: '',
  categories: [],
  tagIds: [],
  image: '',
  description: '',
  companyId: null,
  staffLinks: [],
  characterLinks: [],
  releaseDate: '',
  relatedIds: [],
})
const links = ref([{ label: '', url: '' }])
const imagePreview = ref('')
const submitting = ref(false)

// 会社下拉选项：GET /companies 返回数组（不是 {data:...} 包一层），失败兜底空数组不影响编辑
const companyOptions = ref([])
async function loadCompanyOptions() {
  try {
    const { data } = await api.get('/companies')
    companyOptions.value = Array.isArray(data) ? data : []
  } catch (e) {
    // 拉取失败不阻断编辑（会社可空）
  }
}

// 打开制作人员/角色编辑器：跳转前把当前表单（含未保存内容）暂存草稿，返回后恢复
function openEditor(type) {
  saveDraft({
    form,
    editMode: true,
    imagePreview: imagePreview.value,
    links: links.value,
    relatedOptions: relatedOptions.value,
  })
  router.push(type === 'staffs' ? '/galgame/edit-staffs' : '/galgame/edit-characters')
}

// 已选制作人员/角色数量（按钮旁展示）
const staffCount = computed(() => form.staffLinks.filter((l) => l.id != null).length)
const charCount = computed(() => form.characterLinks.filter((l) => l.id != null).length)

// ---- 标签系统（tags 现在是对象数组 [{id,name,category,spoiler_level,galgame_count}]）----
// 资源 chips：type / language / platform（详情信息卡 meta 区，彩色小 chip）
const resourceTags = computed(() =>
  (detail.value?.tags || []).filter((t) => RESOURCE_CATEGORIES.includes(t.category)),
)

// 标签区：资源类之外（content / meta / technical / sexual），KUNGal Tag 风格
const tagSectionTags = computed(() =>
  (detail.value?.tags || []).filter((t) => TAG_SECTION_CATEGORIES.includes(t.category)),
)

// 标签区类别筛选：all / content / meta / technical / sexual，默认全部
const tagSectionFilter = ref('all')

// 剧透开关：默认关，只显示 spoiler_level=0
const showSpoiler = ref(false)

// 标签区展示的 chip：按类别筛选 + 剧透开关过滤
const filteredTagSectionTags = computed(() => {
  let list = tagSectionTags.value
  if (tagSectionFilter.value !== 'all') {
    list = list.filter((t) => t.category === tagSectionFilter.value)
  }
  if (!showSpoiler.value) {
    list = list.filter((t) => !(t.spoiler_level > 0))
  }
  return list
})

// 编辑回填用：详情已有标签对象数组，预置进标签选择器候选（保证已选标签显示名称）
const detailTagObjects = computed(() =>
  Array.isArray(detail.value?.tags) ? detail.value.tags : [],
)

async function loadDetail({ silent = false } = {}) {
  if (!silent) loading.value = true
  notFound.value = false
  loadError.value = ''
  try {
    const { data } = await api.get(`/galgames/${galgameId}`)
    detail.value = data
    // 已评分回显：详情接口带登录态返回 my_score（当前用户自己的评分）；未评分 / 旧记录无分数为 0
    myScore.value = data.my_score ?? 0
  } catch (e) {
    if (e?.response?.status === 404) {
      notFound.value = true
    } else {
      loadError.value = getErrorMessage(e, '加载失败')
    }
  } finally {
    if (!silent) loading.value = false
  }
}

// 用户评分：未登录先提示；重复评分刷新 rated 状态
async function submitRating() {
  if (!user.value?.id) {
    ElMessage.warning('请先登录后再评分。')
    return
  }
  if (detail.value?.rated) {
    ElMessage.info('你已经评分过了。')
    return
  }
  const score = Number(myScore.value)
  if (!score || score <= 0) {
    ElMessage.warning('请选择评分。')
    return
  }
  try {
    await api.post(`/galgames/${galgameId}/rating`, { score })
    ElMessage.success('评分成功')
    await loadDetail() // 后端已存分，刷新后 my_score 回填展示用户自己的评分
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '评分失败'))
    if (e?.response?.status === 409) {
      await loadDetail() // 已评过：刷新后回填真实分数
    }
  }
}

// 返回按钮：后退栈空（如直接输 URL 进入）时回列表页
function goBack() {
  if (window.history.length > 1) router.back()
  else router.push('/galgame')
}

// 进入编辑模式：用已加载详情数据回填表单
function enterEdit() {
  const d = detail.value || {}
  form.name = d.name || ''
  form.description = d.description || ''
  form.companyId = d.company_id || null
  form.staffLinks = Array.isArray(d.staffs) ? d.staffs.map((s) => ({ id: s.id, description: s.description || '' })) : []
  form.characterLinks = Array.isArray(d.characters) ? d.characters.map((c) => ({ id: c.id, description: c.description || '' })) : []
  form.image = d.image || ''
  form.categories = Array.isArray(d.categories) ? d.categories : []
  form.tagIds = Array.isArray(d.tags) ? d.tags.map((t) => t.id) : []
  form.releaseDate = d.release_date || ''
  // 相关系列回填：预载已选 id 对应的 name，保证 el-select remote 模式选中项能显示名称
  form.relatedIds = Array.isArray(d.related_games) ? d.related_games.map((g) => g.id) : []
  relatedOptions.value = (Array.isArray(d.related_games) ? d.related_games : []).map((g) => ({ id: g.id, name: g.name }))
  links.value = Array.isArray(d.links) && d.links.length
    ? d.links.map((l) => ({ label: l.label || '', url: l.url || '' }))
    : [{ label: '', url: '' }]
  imagePreview.value = d.image ? resolveAssetUrl(d.image) : ''
  editMode.value = true
}

// 取消编辑：还原展示模式（不保存）
function cancelEdit() {
  editMode.value = false
}

function addLink() {
  links.value.push({ label: '', url: '' })
}

function removeLink(i) {
  links.value.splice(i, 1)
}

function removeCover() {
  form.image = ''
  imagePreview.value = ''
}

// 自定义 el-upload 上传：multipart 字段 file → POST /galgames/image → { url }
async function uploadImage(options) {
  const file = options?.file
  if (!file) return
  const fd = new FormData()
  fd.append('file', file)
  try {
    const { data } = await api.post('/galgames/image', fd)
    form.image = data.url
    imagePreview.value = resolveAssetUrl(data.url)
    ElMessage.success('封面上传成功')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '封面上传失败'))
  }
}

async function submit() {
  const name = form.name.trim()
  if (!name) {
    ElMessage.error('请填写 Galgame 名称。')
    return
  }
  if (!form.tagIds.length) {
    ElMessage.error('请至少选择 1 个标签。')
    return
  }
  const payload = {
    name,
    categories: form.categories.filter((c) => c && c.trim()),
    description: form.description.trim(),
    image: form.image,
    company_id: form.companyId || null,
    staffs: form.staffLinks.filter((l) => l.id != null).map((l) => ({ id: l.id, description: (l.description || '').trim() })),
    characters: form.characterLinks.filter((l) => l.id != null).map((l) => ({ id: l.id, description: (l.description || '').trim() })),
    release_date: form.releaseDate || null,
    links: links.value
      .map((l) => ({ label: l.label.trim(), url: l.url.trim() }))
      .filter((l) => l.url),
    tag_ids: form.tagIds.filter((id) => id != null),
    related_ids: form.relatedIds.filter(Boolean),
  }
  submitting.value = true
  try {
    await api.put(`/galgames/${galgameId}`, payload)
    // 保存成功提示分场景：管理员改正式记录 = 原地保存；创建者改已上架条目 = 后端创建影子修改申请
    if (!isAdmin.value && detail.value?.status === 'approved') {
      ElMessage.success('修改申请已提交，等待管理员审核')
    } else {
      ElMessage.success('保存成功')
    }
    editMode.value = false
    await loadDetail()
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '保存失败'))
  } finally {
    submitting.value = false
  }
}

// 删除：二次确认后 DELETE，成功回列表
async function remove() {
  try {
    await ElMessageBox.confirm('确定删除该 Galgame 吗？删除后不可恢复。', '删除确认', { type: 'warning' })
  } catch {
    return // 用户取消，什么都不做
  }
  try {
    await api.delete(`/galgames/${galgameId}`)
    ElMessage.success('删除成功')
    router.push('/galgame')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除失败'))
  }
}

// 通过审核：POST /galgames/{id}/review { status: 'approved' }，成功后刷新详情。
// 提交者恰为当前用户（管理员审核自己的提交）时 +10，refreshMoe 检测增量弹「+n萌点」；审核他人无变化不弹。
async function approve() {
  try {
    await api.post(`/galgames/${galgameId}/review`, { status: 'approved' })
    ElMessage.success('已通过审核')
    refreshMoe()
    await loadDetail()
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  }
}

// 拒绝：弹框填理由 → POST /galgames/{id}/review { status: 'rejected', reason }，成功后刷新详情
async function reject() {
  let reason = ''
  try {
    const { value } = await ElMessageBox.prompt('请填写拒绝理由', '拒绝', {
      type: 'warning',
      inputPlaceholder: '拒绝理由',
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && String(v).trim() ? true : '请填写拒绝理由。'),
    })
    reason = (value || '').trim()
  } catch {
    return // 用户取消，什么都不做
  }
  if (!reason) {
    ElMessage.warning('请填写拒绝理由。')
    return
  }
  try {
    await api.post(`/galgames/${galgameId}/review`, { status: 'rejected', reason })
    ElMessage.success('已拒绝')
    await loadDetail()
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  }
}

function openLink(url) {
  if (!url) return
  window.open(url, '_blank', 'noopener')
}

// 详情页会社名点击 → 跳转会社详情页
function goCompany(id) {
  if (id == null) return
  router.push('/company/' + id)
}

// 详情页制作人员名点击 → 跳转制作人员详情页
function goStaff(id) {
  if (id == null) return
  router.push('/staff/' + id)
}

// 详情页角色名点击 → 跳转角色详情页
function goCharacter(id) {
  if (id == null) return
  router.push('/character/' + id)
}

// 角色栏封面加载失败时回退到占位块
function charCoverError(c) {
  c._coverError = true
}

// 详情页「条目贡献者」小字 → 跳转贡献者列表页
function goContributors() {
  router.push('/galgame/' + detail.value.id + '/contributors')
}

// 详情页标签 chip / 资源 chip 点击 → 跳转标签详情页
function goTag(id) {
  if (id == null) return
  router.push('/galgame/tag/' + id)
}

// 大图封面加载失败时回退到占位块
function coverImgError() {
  if (detail.value) detail.value._coverError = true
}

// ---- 评论区交互逻辑（同 PostView 评论区，去掉置顶）----

function goLogin() {
  router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
}

// 评论的父评论对象（评论列表是平铺数组，按 parent_id 在 detail.replies 中查找），找不到返回 null
function parentOf(r) {
  if (r.parent_id == null || r.parent_id === '') return null
  return (detail.value?.replies || []).find((x) => Number(x.id) === Number(r.parent_id)) || null
}

// 父评论作者名：优先取列表中的父评论；父评论被删除后 parent_id 会置 NULL，
// 此时不能靠 parent_id 判断是否嵌套，改由创建时的 parent_author 快照兜底
function parentNameOf(r) {
  return parentOf(r)?.author || r.parent_author || ''
}

async function toggleReplyLike(r) {
  if (!requireLogin(router)) return
  if (replyLiking.has(r.id)) return
  replyLiking.add(r.id)
  try {
    const { data } = await api.post(`/galgame-replies/${r.id}/like`)
    r.liked = data.liked
    r.like_count = data.like_count
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '点赞失败'))
  } finally {
    replyLiking.delete(r.id)
  }
}

// 评论点踩（与点赞独立，不互斥）
async function toggleReplyDislike(r) {
  if (!requireLogin(router)) return
  if (replyDisliking.has(r.id)) return
  replyDisliking.add(r.id)
  try {
    const { data } = await api.post(`/galgame-replies/${r.id}/dislike`)
    r.disliked = data.disliked
    r.dislike_count = data.dislike_count
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '操作失败'))
  } finally {
    replyDisliking.delete(r.id)
  }
}

async function submitReply() {
  const content = replyForm.content.trim()
  if (!content) {
    replyStatus.value = '评论内容不能为空。'
    replyStatusError.value = true
    return
  }
  // 长评前端提前拦截：不足 300 字不发请求（后端同样校验，冗余双保险）
  if (replyType.value === 'long' && content.length < 301) {
    ElMessage.warning('长评至少需要 300 字。')
    return
  }

  replySubmitting.value = true
  replyStatus.value = '正在发表评论...'
  replyStatusError.value = false
  try {
    const body = { content }
    if (replyType.value === 'long') body.is_long = true
    if (replyingTo.value) body.parent_id = replyingTo.value.id
    if (replyForm.images.length) body.images = [...replyForm.images]
    await api.post(`/galgames/${galgameId}/replies`, body)
    replyForm.content = ''
    replyForm.images = []
    replyingTo.value = null
    replyStatus.value = ''
    ElMessage.success('评论已发表')
    // 每日首次评论 +5 萌点：refreshMoe 检测增量弹「+n萌点」并同步（非首次不加分则不弹）
    refreshMoe()
    // 静默刷新详情，评论区随 detail.replies 更新（不清空 myScore 回填逻辑）
    await loadDetail({ silent: true })
  } catch (e) {
    const msg = getErrorMessage(e, '发表评论失败')
    replyStatus.value = msg
    replyStatusError.value = true
    ElMessage.error(msg)
  } finally {
    replySubmitting.value = false
  }
}

// 删除自己的评论（仅作者本人可见该入口）
async function deleteReply(reply) {
  try {
    await ElMessageBox.confirm(
      '确定删除这条评论吗？删除后不可恢复。',
      '删除评论',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return // 用户取消
  }
  try {
    await api.delete(`/galgame-replies/${reply.id}`)
    ElMessage.success('评论已删除')
    if (replyingTo.value && Number(replyingTo.value.id) === Number(reply.id)) {
      replyingTo.value = null
    }
    // 静默刷新详情，同步评论列表
    await loadDetail({ silent: true })
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '删除评论失败'))
  }
}

// 打开举报弹窗（仅评论，需登录）
function openReport(r) {
  if (!requireLogin(router)) return
  reportTarget.value = r
  reportReason.value = ''
  reportDialogVisible.value = true
}

// 提交举报
async function submitReport() {
  if (!reportTarget.value) return
  reportSubmitting.value = true
  try {
    await api.post(`/galgame-replies/${reportTarget.value.id}/report`, { reason: reportReason.value.trim() })
    reportDialogVisible.value = false
    ElMessage.success('举报已提交，感谢反馈')
  } catch (e) {
    ElMessage.error(getErrorMessage(e, '举报失败'))
  } finally {
    reportSubmitting.value = false
  }
}

onMounted(async () => {
  loadCompanyOptions()
  await loadDetail()
  // 从编辑器返回：恢复跳转前草稿（含未保存的编辑表单 + 编辑模式）
  const draft = loadDraft()
  if (draft && draft.form) {
    Object.assign(form, draft.form)
    if (Array.isArray(draft.links)) links.value = draft.links
    if (draft.imagePreview) imagePreview.value = draft.imagePreview
    if (Array.isArray(draft.relatedOptions)) relatedOptions.value = draft.relatedOptions
    editMode.value = !!draft.editMode
    clearDraft()
  }
})
</script>

<style scoped>
.page {
  width: 100%;
}
.gal-detail-center {
  max-width: 900px;
  margin: 0 auto;
  padding: 24px 16px 0;
}
.gal-detail-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.gal-detail-actions {
  display: flex;
  gap: 8px;
}
.gal-detail-card {
  display: flex;
  gap: 24px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  margin-bottom: 16px;
}
.gal-detail-cover {
  flex-shrink: 0;
  width: 280px;
  height: 360px;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
}
.gal-detail-cover img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.gal-detail-cover-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 13px;
  background: #f0f2f5;
}
.gal-detail-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}
.gal-detail-name-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 8px;
}
.gal-detail-name-row .gal-detail-name {
  margin: 0;
}
.gal-detail-name {
  font-size: 24px;
  font-weight: 700;
  color: #303133;
  margin: 0 0 8px;
}
.gal-reject-reason {
  margin-bottom: 12px;
}
.gal-detail-staff {
  margin: 12px 0 0;
  color: #888;
  font-size: 14px;
}
.gal-staff-link {
  color: #409eff;
  cursor: pointer;
  text-decoration: none;
  transition: color 0.15s;
}
.gal-staff-link:hover {
  color: #409eff;
  text-decoration: underline;
}
.gal-detail-desc {
  margin: 12px 0 0;
  color: #666;
  font-size: 14px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.gal-detail-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
  margin-top: 16px;
  color: #909399;
  font-size: 13px;
}
/* 评分高亮块：大号金色分数 + 人数（详情页，醒目展示） */
.gal-big-rating {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-top: 14px;
}
.gal-big-score {
  font-size: 34px;
  font-weight: 800;
  color: #f7b731;
  line-height: 1;
}
.gal-big-denom {
  font-size: 16px;
  color: #909399;
}
.gal-big-count {
  font-size: 14px;
  color: #e6a23c;
  margin-left: 4px;
}
.gal-big-none {
  font-size: 20px;
  font-weight: 600;
  color: #909399;
}
/* 分类行（旧系统 section_key → 中文小 tag）：复用 .gal-tag，仅容器/标签文字样式 */
.gal-cat-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 10px;
}
.gal-cat-label {
  color: #909399;
  font-size: 13px;
}
.gal-rate-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
  color: #909399;
  font-size: 13px;
}
.gal-rated-hint {
  color: #909399;
  font-size: 13px;
}
.gal-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.gal-tag {
  color: #409eff;
  background: #ecf5ff;
  border-radius: 999px;
  padding: 2px 10px;
  font-size: 12px;
  line-height: 1.6;
}
/* 资源 chips（信息卡 meta 区）：type 蓝 / language 灰 / platform 绿 */
.gal-resource-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.gal-resource-chip {
  border-radius: 999px;
  padding: 1px 10px;
  font-size: 12px;
  line-height: 1.7;
  cursor: pointer;
  transition: opacity 0.15s;
}
.gal-resource-chip:hover {
  opacity: 0.8;
}
.gal-resource-chip.res-type {
  color: #409eff;
  background: #ecf5ff;
}
.gal-resource-chip.res-language {
  color: #909399;
  background: #f4f4f5;
}
.gal-resource-chip.res-platform {
  color: #67c23a;
  background: #f0f9eb;
}
/* 标签区（KUNGal Tag 风格） */
.gal-tag-section {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #f0f2f5;
}
.gal-tag-section-title {
  margin: 0 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: #303133;
}
.gal-tag-filter-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}
.gal-tag-filter-chip {
  border: 1px solid #e4e7ed;
  background: #fff;
  color: #666;
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-tag-filter-chip:hover {
  background: #ecf5ff;
  color: #409eff;
  border-color: #b3d8ff;
}
.gal-tag-filter-chip.active {
  background: #ecf5ff;
  color: #409eff;
  font-weight: 600;
  border-color: #409eff;
}
.gal-spoiler-toggle {
  display: inline-flex;
  align-items: center;
  margin-left: 4px;
  color: #909399;
  font-size: 13px;
}
.gal-tag-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.gal-tag-chip {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  padding: 3px 12px;
  font-size: 12px;
  line-height: 1.6;
  cursor: pointer;
  transition: opacity 0.15s;
}
.gal-tag-chip:hover {
  opacity: 0.8;
}
.gal-tag-chip.cat-content {
  color: #409eff;
  background: #ecf5ff;
}
.gal-tag-chip.cat-meta,
.gal-tag-chip.cat-technical {
  color: #67c23a;
  background: #f0f9eb;
}
.gal-tag-chip.cat-sexual {
  color: #f56c9a;
  background: #fdf2f7;
}
.gal-tag-count {
  margin-left: 4px;
  font-size: 11px;
  opacity: 0.75;
}
.gal-tag-spoiler {
  margin-left: 4px;
  font-size: 11px;
  color: #e6a23c;
}
.gal-tag-spoiler.lvl-2 {
  color: #f56c6c;
}
.gal-tag-empty {
  margin: 0;
  color: #909399;
  font-size: 13px;
}
.gal-links {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 12px;
}
.gal-link {
  border: 1px solid #b3d8ff;
  background: #ecf5ff;
  color: #409eff;
  border-radius: 6px;
  padding: 3px 10px;
  font-size: 12px;
  line-height: 1.5;
  cursor: pointer;
  transition: all 0.15s;
}
.gal-link:hover {
  background: #409eff;
  color: #fff;
}
.gal-no-link {
  color: #b0b3b8;
  font-size: 12px;
}
.gal-detail-skel {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
}
.gal-detail-empty {
  text-align: center;
  padding: 64px 0;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  margin-bottom: 16px;
}
.gal-detail-empty-text {
  font-size: 18px;
  font-weight: 600;
  color: #909399;
  margin: 0 0 16px;
}
.gal-detail-error {
  margin-bottom: 16px;
}
/* 编辑模式（与 AddGalgameView 一致） */
.add-card {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 24px;
  margin-bottom: 16px;
}
.add-tags-select {
  width: 100%;
}
.add-date-picker {
  width: 100%;
}
.add-cover {
  display: flex;
  align-items: flex-start;
  gap: 16px;
  flex-wrap: wrap;
}
.add-cover-preview {
  display: flex;
  align-items: flex-start;
  gap: 8px;
}
.add-cover-preview img {
  width: 120px;
  height: 150px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #e4e7ed;
  background: #f5f7fa;
}
.add-hint {
  color: #b0b3b8;
  font-size: 12px;
  margin-top: 4px;
}
.add-links {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.add-link-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.add-link-label {
  width: 140px;
  flex-shrink: 0;
}
.add-link-url {
  flex: 1;
  min-width: 0;
}
.add-link-btn {
  align-self: flex-start;
}
/* 详情页展示模式 tab（制作人员 / 角色 / 评论 同级切换） */
.gal-section-tabs {
  display: flex;
  gap: 10px;
  margin-top: 20px;
  margin-bottom: 16px;
}
.gal-section-tab {
  border: 1px solid #e4e7ed;
  background: #fff;
  color: #606266;
  border-radius: 6px;
  padding: 10px 22px;
  font-size: 15px;
  cursor: pointer;
  transition: all 0.2s;
}
.gal-section-tab:hover {
  color: #409eff;
  border-color: #409eff;
}
.gal-section-tab.active {
  background: #409eff;
  border-color: #409eff;
  color: #fff;
}
.gal-section-pane {
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}
.gal-relation-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.gal-relation-item {
  display: flex;
  align-items: baseline;
  gap: 10px;
  font-size: 15px;
}
.gal-relation-desc {
  color: #909399;
  font-size: 13px;
  flex-shrink: 0;
  max-width: 40%;
}
/* 同职业一组：人员横向排开，超长自动折行到下一行 */
.gal-staff-group-members {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 2px 8px;
  min-width: 0;
}
.gal-link-entry {
  display: flex;
  align-items: center;
  gap: 12px;
}
.gal-link-count {
  color: #909399;
  font-size: 13px;
}
.gal-section-empty {
  color: #909399;
  text-align: center;
  padding: 24px 0;
  margin: 0;
}
/* 角色 tab：方框网格，一行 5 个（窄屏降 3 / 2 列） */
.gal-char-grid {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 16px;
}
@media (max-width: 900px) {
  .gal-char-grid { grid-template-columns: repeat(3, 1fr); }
}
@media (max-width: 600px) {
  .gal-char-grid { grid-template-columns: repeat(2, 1fr); }
}
/* 角色栏卡片：纵向方框卡片（封面在上，名称/定位在下） */
.gal-char-card {
  flex-direction: column;
  align-items: center;
  text-align: center;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 12px;
  background: #fff;
}
.gal-char-cover {
  width: 100%;
  border-radius: 6px;
  overflow: hidden;
  background: #f0f2f5;
}
.gal-char-cover img {
  width: 100%;
  height: auto;
  display: block;
}
.gal-char-cover-placeholder {
  width: 64px;
  height: 84px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 12px;
  background: #f0f2f5;
}
.gal-char-info {
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}
/* ---- 评论区（同 PostView 评论区样式，去掉置顶）---- */
.gal-comment-hint {
  margin-top: 16px;
}
.replies-title {
  font-size: 15px;
}
.reply-empty {
  color: #909399;
  text-align: center;
  padding: 24px 0;
}
.reply {
  padding: 12px 0;
  border-bottom: 1px solid #f0f0f0;
}
.reply:last-child {
  border-bottom: none;
}
.reply-top {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: #666;
  margin-bottom: 6px;
}
.reply-author {
  color: #333;
  font-weight: 500;
}
.author-link {
  color: #409eff;
  text-decoration: none;
}
.author-link:hover {
  text-decoration: underline;
}
.dot {
  color: #ccc;
}
.time {
  color: #999;
}
.reply-content {
  white-space: pre-wrap;
  word-break: break-word;
  line-height: 1.6;
  color: #303133;
  margin: 0 0 8px;
}
.reply-parent {
  margin: 0 0 4px;
  font-size: 13px;
  color: #909399;
}
.reply-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}
.reply-form {
  margin-top: 8px;
}
.replying-to {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 13px;
  color: #606266;
}
.replying-label {
  background: #f0f7ff;
  color: #409eff;
  border-radius: 4px;
  padding: 2px 8px;
}
.login-prompt {
  text-align: center;
  padding: 16px 0;
  color: #606266;
}
.login-prompt p {
  margin: 0 0 12px;
}
.reply-form-actions {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
}
.form-status {
  font-size: 13px;
  color: #67c23a;
}
.form-status.error {
  color: #f56c6c;
}
.like-button {
  border: 1px solid #dcdfe6;
  background: #fff;
  color: #606266;
  border-radius: 999px;
  padding: 6px 16px;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  transition: all 0.2s;
}
.like-button:hover {
  color: #f56c9a;
  border-color: #f56c9a;
}
.like-button.liked {
  color: #f56c9a;
  border-color: #f56c9a;
  background: #fdf2f7;
}
.like-button:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.like-button.small {
  padding: 3px 10px;
  font-size: 12px;
}
/* 点踩按钮：灰色系与点赞的粉色区分 */
.like-button.dislike:hover {
  color: #909399;
  border-color: #909399;
}
.like-button.dislike.disliked {
  color: #909399;
  border-color: #909399;
  background: #f4f4f5;
}
/* 评论输入区 emoji 面板 */
.reply-emoji-picker {
  margin-top: 8px;
}
/* emoji 开关按钮 */
.emoji-toggle {
  font-size: 18px;
  line-height: 1;
  padding: 4px 6px;
}
.emoji-toggle.active {
  color: #409eff;
}
/* 图片开关按钮：与 emoji-toggle 同款 */
.image-toggle {
  font-size: 18px;
  line-height: 1;
  padding: 4px 6px;
}
/* 评论/长评切换 */
.reply-type-toggle {
  margin-bottom: 8px;
}
/* 评论列表查看 tab（短评/长评），仿 reply-type-toggle */
.reply-view-toggle {
  margin-bottom: 12px;
}
/* 评论作者评分小标签（金色，紧跟昵称） */
.reply-rating {
  color: #e6a23c;
  font-weight: 600;
  margin-left: 6px;
  font-size: 12px;
}
/* 评论图片（已发表） */
.reply-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 6px;
}
.reply-images img {
  max-width: 240px;
  max-height: 180px;
  border-radius: 6px;
  cursor: pointer;
  object-fit: cover;
}
/* 评论图片预览（上传后未发表） */
.reply-image-previews {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.reply-image-preview {
  position: relative;
}
.reply-image-preview img {
  width: 48px;
  height: 48px;
  object-fit: cover;
  border-radius: 4px;
}
.reply-image-preview button {
  position: absolute;
  top: -6px;
  right: -6px;
  width: 18px;
  height: 18px;
  line-height: 1;
  border: none;
  border-radius: 50%;
  background: #f56c6c;
  color: #fff;
  cursor: pointer;
  font-size: 12px;
  padding: 0;
}
/* ---- 画廊（详情页 tab，多图上传 / 删除 / 展示）---- */
.gal-gallery-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 12px;
}
.gal-gallery-item {
  position: relative;
  border-radius: 8px;
  overflow: hidden;
}
.gal-gallery-item img {
  width: 100%;
  height: 120px;
  object-fit: cover;
  border-radius: 8px;
  cursor: pointer;
  display: block;
  transition: opacity 0.15s;
}
.gal-gallery-item img:hover {
  opacity: 0.85;
}
.gal-gallery-del {
  position: absolute;
  top: 4px;
  right: 4px;
  background: rgba(0, 0, 0, 0.45);
  color: #fff;
  border-radius: 4px;
  padding: 0 6px;
  line-height: 1.8;
}
.gal-gallery-del:hover {
  background: #f56c6c;
  color: #fff;
}
.gal-gallery-upload {
  margin-top: 14px;
  padding-top: 14px;
  border-top: 1px solid #f0f2f5;
}
/* ---- 相关系列（详情页底部展示框 + 编辑表单多选）---- */
.gal-related-section {
  margin-top: 16px;
  margin-bottom: 16px;
  background: #fff;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  padding: 20px 24px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
}
.gal-related-title {
  margin: 0 0 14px;
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}
.gal-related-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}
.gal-related-card {
  width: 140px;
  cursor: pointer;
  border-radius: 8px;
  overflow: hidden;
  transition: transform 0.15s, box-shadow 0.15s;
}
.gal-related-card:hover {
  transform: translateY(-2px);
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.12);
}
.gal-related-cover {
  width: 100%;
  height: 90px;
  border-radius: 6px;
  overflow: hidden;
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
}
.gal-related-cover img {
  width: 100%;
  height: 90px;
  object-fit: cover;
  display: block;
}
.gal-related-placeholder {
  width: 100%;
  height: 90px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 12px;
  background: #f0f2f5;
}
.gal-related-name {
  display: block;
  margin-top: 6px;
  font-size: 13px;
  color: #303133;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.gal-related-select {
  width: 100%;
}
</style>
