import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../views/HomeView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', name: 'home', component: HomeView },
    { path: '/login', name: 'login', component: () => import('../views/AuthView.vue') },
    { path: '/compose', name: 'compose', component: () => import('../views/ComposeView.vue') },
    { path: '/user/:id', name: 'profile', component: () => import('../views/ProfileView.vue') },
    { path: '/user/:id/following', name: 'profile-following', component: () => import('../views/FollowListView.vue'), props: { tab: 'following' } },
    { path: '/user/:id/followers', name: 'profile-followers', component: () => import('../views/FollowListView.vue'), props: { tab: 'followers' } },
    { path: '/post/:id', name: 'post', component: () => import('../views/PostView.vue') },
    { path: '/tag/:category', name: 'tag-category', component: () => import('../views/SectionView.vue') },
    { path: '/tag/:category/:section', name: 'tag-section', component: () => import('../views/SectionView.vue') },
    { path: '/messages', name: 'messages', component: () => import('../views/MessagesView.vue') },
    { path: '/messages/:userId', name: 'conversation', component: () => import('../views/ConversationView.vue') },
  ],
})

export default router
