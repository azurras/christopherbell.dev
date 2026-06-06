/**
 * Centralized API routes (versioned URLs).
 *
 * Keep endpoints here to avoid repetition and make upgrades simple.
 */
export const API = {
  admin: {
    activity: '/api/admin/activity/2026-05-09',
  },
  canesBoxTracker: {
    history: '/api/canes-box-tracker/2026-06-04/history',
    collect: '/api/canes-box-tracker/2026-06-04/collect',
    approveMetro: (weekStartDate, metroName) =>
      `/api/canes-box-tracker/2026-06-04/${encodeURIComponent(weekStartDate)}/metros/${encodeURIComponent(metroName)}/approve`,
    rejectMetro: (weekStartDate, metroName) =>
      `/api/canes-box-tracker/2026-06-04/${encodeURIComponent(weekStartDate)}/metros/${encodeURIComponent(metroName)}/reject`,
    manualPrice: '/api/canes-box-tracker/2026-06-04/manual-prices',
  },
  accounts: {
    base: '/api/accounts/2024-12-15',
    login: '/api/accounts/2024-12-15/login',
    create: '/api/accounts/2024-12-15/create',
    approve: (id) => `/api/accounts/2025-09-03/approve/${encodeURIComponent(id)}`,
    update: '/api/accounts/2025-09-14',
    passwordResetRequest: '/api/accounts/2024-12-15/password-reset/request',
    passwordResetConfirm: '/api/accounts/2024-12-15/password-reset/confirm',
    me: '/api/accounts/2025-09-03/me',
    profile: (username) => `/api/accounts/2025-09-14/profile/${encodeURIComponent(username)}`,
    follow: (username) => `/api/accounts/2025-09-14/profile/${encodeURIComponent(username)}/follow`,
    setTrust: (username) => `/api/accounts/2026-06-02/trust/${encodeURIComponent(username)}`,
    clearTrust: (username, type) => `/api/accounts/2026-06-02/trust/${encodeURIComponent(username)}/${encodeURIComponent(type)}`,
    search: (username, limit = 8) => {
      const params = new URLSearchParams({
        username: String(username || ''),
        limit: String(limit),
      });
      return `/api/accounts/2025-09-14/search?${params}`;
    },
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
    byAccount: (accountId) => `/api/posts/2025-09-14/account/${encodeURIComponent(accountId)}`,
    hideThread: (id) => `/api/posts/2026-06-02/${encodeURIComponent(id)}/hide-thread`,
    unhideThread: (rootId) => `/api/posts/2026-06-02/${encodeURIComponent(rootId)}/hide-thread`,
  },
  notifications: {
    base: '/api/notifications/2025-09-14',
    unreadCount: '/api/notifications/2025-09-14/unread-count',
    preferences: '/api/notifications/2025-09-14/preferences',
    markRead: (id) => `/api/notifications/2025-09-14/${encodeURIComponent(id)}/read`,
  },
  location: {
    zipCoordinate: (zipCode) => `/api/location/zip/${encodeURIComponent(zipCode)}`,
    importCensusZipCoordinates: '/api/location/zip/import/census',
  },
  messages: {
    base: '/api/messages/2025-09-14',
    conversations: '/api/messages/2025-09-14/conversations',
    conversation: (username) => `/api/messages/2025-09-14/conversation/${encodeURIComponent(username)}`,
  },
  whatsForLunch: {
    restaurants: '/api/whatsforlunch/restaurant/2025-09-12',
    today: '/api/whatsforlunch/restaurant/2026-05-17/today',
    nearby: ({ latitude, longitude }, cuisines = [], radiusMiles = 15, useSavedPreferences = false) => {
      const params = new URLSearchParams({
        latitude: String(latitude),
        longitude: String(longitude),
        radiusMiles: String(radiusMiles),
        useSavedPreferences: String(useSavedPreferences),
      });
      cuisines.forEach((cuisine) => {
        if (cuisine) params.append('cuisine', cuisine);
      });
      return `/api/whatsforlunch/restaurant/2026-05-17/nearby?${params}`;
    },
    nearbyByZip: (zipCode, cuisines = [], radiusMiles = 15, useSavedPreferences = false) => {
      const params = new URLSearchParams({
        radiusMiles: String(radiusMiles),
        useSavedPreferences: String(useSavedPreferences),
      });
      cuisines.forEach((cuisine) => {
        if (cuisine) params.append('cuisine', cuisine);
      });
      return `/api/whatsforlunch/restaurant/2026-05-17/nearby/zip/${encodeURIComponent(zipCode)}?${params}`;
    },
    preferences: '/api/whatsforlunch/restaurant/2026-05-17/preferences',
    restaurant: (id) => `/api/whatsforlunch/restaurant/2026-05-17/profile/${encodeURIComponent(id)}`,
    sessions: '/api/whatsforlunch/restaurant/2026-05-17/sessions',
    session: (id) => `/api/whatsforlunch/restaurant/2026-05-17/sessions/${encodeURIComponent(id)}`,
    sessionJoin: (id) => `/api/whatsforlunch/restaurant/2026-05-17/sessions/${encodeURIComponent(id)}/join`,
    sessionVote: (id) => `/api/whatsforlunch/restaurant/2026-05-17/sessions/${encodeURIComponent(id)}/vote`,
    sessionRestaurants: (id) => `/api/whatsforlunch/restaurant/2026-05-17/sessions/${encodeURIComponent(id)}/restaurants`,
    rateRestaurant: '/api/whatsforlunch/restaurant/2026-05-17/rating',
    favorites: '/api/whatsforlunch/restaurant/2026-05-17/favorites',
    favoriteRestaurant: '/api/whatsforlunch/restaurant/2026-05-17/favorite',
    topRated: (limit = 10) => `/api/whatsforlunch/restaurant/2026-05-17/top-rated?limit=${encodeURIComponent(limit)}`,
    deleteRestaurant: (id) => `/api/whatsforlunch/restaurant/2025-09-13/${encodeURIComponent(id)}`,
    deleteTodayPick: (id) => `/api/whatsforlunch/restaurant/2026-05-17/today/${encodeURIComponent(id)}`,
    importOpenStreetMap: '/api/whatsforlunch/restaurant/2026-05-17/import/openstreetmap',
    dedupeNames: '/api/whatsforlunch/restaurant/2026-05-17/dedupe-names',
  },
  vehicles: {
    base: '/api/vehicles/2026-05-09',
    createFromVin: '/api/vehicles/2026-05-09/vin',
    createFromVins: '/api/vehicles/2026-05-09/vins',
    dataCollectionState: '/api/vehicles/2026-05-09/data-collection-state',
    decodeVin: '/api/vehicles/2026-05-09/vin/decode',
  },
  blog: {
    posts: '/api/blog/v1/posts',
  },
};
