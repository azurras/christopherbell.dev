/**
 * Centralized API routes (versioned URLs).
 *
 * Keep endpoints here to avoid repetition and make upgrades simple.
 */
export const API = {
  admin: {
    activity: '/api/admin/activity/2026-05-09',
  },
  accounts: {
    base: '/api/accounts/2024-12-15',
    login: '/api/accounts/2024-12-15/login',
    create: '/api/accounts/2024-12-15/create',
    me: '/api/accounts/2025-09-03/me',
    profile: (username) => `/api/accounts/2025-09-14/profile/${encodeURIComponent(username)}`,
    follow: (username) => `/api/accounts/2025-09-14/profile/${encodeURIComponent(username)}/follow`,
  },
  reports: {
    create: '/api/reports/2025-09-03',
    list: '/api/reports/2025-09-03',
    resolve: (id) => `/api/reports/2025-09-03/${encodeURIComponent(id)}/resolve`,
  },
  posts: {
    base: '/api/posts/2025-09-14',
    feed: '/api/posts/2025-09-14/feed',
    followingFeed: '/api/posts/2025-09-14/following/feed',
    userFeed: (username) => `/api/posts/2025-09-14/user/${encodeURIComponent(username)}/feed`,
    meFeed: '/api/posts/2025-09-14/me/feed',
    create: '/api/posts/2025-09-14/create',
    byId: (id) => `/api/posts/2025-09-14/${encodeURIComponent(id)}`,
    like: (id) => `/api/posts/2025-09-14/${encodeURIComponent(id)}/like`,
    thread: (id) => `/api/posts/2025-09-14/${encodeURIComponent(id)}/thread`,
  },
  notifications: {
    base: '/api/notifications/2025-09-14',
    unreadCount: '/api/notifications/2025-09-14/unread-count',
    markRead: (id) => `/api/notifications/2025-09-14/${encodeURIComponent(id)}/read`,
  },
  vehicles: {
    decodeVin: '/api/vehicles/2026-05-09/vin/decode',
  },
};
