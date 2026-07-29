function discoveryPageUrl(path, cursor, size) {
  const params = new URLSearchParams({ size: String(size) });
  if (cursor) params.set('cursor', String(cursor));
  return `/api/posts/2026-07-28/discovery/${path}?${params}`;
}

/**
 * Centralized API routes (versioned URLs).
 *
 * Keep endpoints here to avoid repetition and make upgrades simple.
 */
export const API = {
  admin: {
    activity: '/api/admin/activity/2026-05-09',
    activityPage: ({ page = 0, size = 25, action = '', targetType = '', actor = '',
      from = '', to = '' } = {}) => {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (action) params.set('action', String(action));
      if (targetType) params.set('targetType', String(targetType));
      if (actor) params.set('actor', String(actor));
      if (from) params.set('from', String(from));
      if (to) params.set('to', String(to));
      return `/api/admin/activity/2026-07-26?${params}`;
    },
    commandCenter: {
      snapshot: '/api/admin/command-center/2026-07-12/snapshot',
      logs: '/api/admin/command-center/2026-07-12/logs',
      challenges: '/api/admin/command-center/2026-07-12/action-challenges',
      actions: '/api/admin/command-center/2026-07-12/actions',
      cancel: '/api/admin/command-center/2026-07-12/actions/cancel',
    },
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
    adminPage: ({ page = 0, size = 25, sort = 'createdOn', direction = 'desc',
      status = '', role = '', text = '' } = {}) => {
      const params = new URLSearchParams({
        page: String(page),
        size: String(size),
        sort: String(sort),
        direction: String(direction),
      });
      if (status) params.set('status', String(status));
      if (role) params.set('role', String(role));
      if (text) params.set('text', String(text));
      return `/api/accounts/2026-07-26/admin?${params}`;
    },
    login: '/api/accounts/2024-12-15/login',
    logout: '/api/accounts/2024-12-15/logout',
    create: '/api/accounts/2024-12-15/create',
    approve: (id) => `/api/accounts/2025-09-03/approve/${encodeURIComponent(id)}`,
    update: '/api/accounts/2025-09-14',
    updateSharedFolderPermissions: (id) =>
      `/api/accounts/2026-07-17/${encodeURIComponent(id)}/shared-folder-permissions`,
    updateMusicPermissions: (id) =>
      `/api/accounts/2026-07-28/${encodeURIComponent(id)}/music-permissions`,
    passwordResetRequest: '/api/accounts/2024-12-15/password-reset/request',
    passwordResetConfirm: '/api/accounts/2024-12-15/password-reset/confirm',
    me: '/api/accounts/2025-09-03/me',
    federation: '/api/accounts/2026-07-28/self/federation',
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
    page: (query = {}) => {
      const params = new URLSearchParams();
      params.set('page', String(query.page ?? 0));
      params.set('size', String(query.size ?? 25));
      for (const key of ['status', 'reportType', 'targetType', 'reporter', 'from', 'to']) {
        if (query[key]) params.set(key, query[key]);
      }
      return `/api/reports/2026-07-26?${params.toString()}`;
    },
    resolve: (id) => `/api/reports/2025-09-03/${encodeURIComponent(id)}/resolve`,
  },
  posts: {
    base: '/api/posts/2025-09-14',
    feedPage: '/api/posts/2026-07-26/feed',
    followingFeedPage: '/api/posts/2026-07-26/following/feed',
    userFeedPage: (username) => `/api/posts/2026-07-26/user/${encodeURIComponent(username)}/feed`,
    meFeedPage: '/api/posts/2026-07-26/me/feed',
    feed: '/api/posts/2025-09-14/feed',
    followingFeed: '/api/posts/2025-09-14/following/feed',
    userFeed: (username) => `/api/posts/2025-09-14/user/${encodeURIComponent(username)}/feed`,
    meFeed: '/api/posts/2025-09-14/me/feed',
    create: '/api/posts/2025-09-14/create',
    byId: (id) => `/api/posts/2025-09-14/${encodeURIComponent(id)}`,
    edit: (id) => `/api/posts/2026-07-26/${encodeURIComponent(id)}`,
    like: (id) => `/api/posts/2026-07-29/${encodeURIComponent(id)}/like`,
    thread: (id) => `/api/posts/2025-09-14/${encodeURIComponent(id)}/thread`,
    byAccount: (accountId) => `/api/posts/2026-07-29/account/${encodeURIComponent(accountId)}`,
    hideThread: (id) => `/api/posts/2026-06-02/${encodeURIComponent(id)}/hide-thread`,
    unhideThread: (rootId) => `/api/posts/2026-06-02/${encodeURIComponent(rootId)}/hide-thread`,
    discovery: {
      new: (cursor = '', size = 12) => discoveryPageUrl('new', cursor, size),
      fading: (cursor = '', size = 12) => discoveryPageUrl('fading', cursor, size),
      revived: (cursor = '', size = 12) => discoveryPageUrl('revived', cursor, size),
      topics: (cursor = '', size = 12) => discoveryPageUrl('topics', cursor, size),
      people: '/api/posts/2026-07-28/discovery/people',
      topic: (topic, cursor = '', size = 12) => discoveryPageUrl(
        `topic/${encodeURIComponent(String(topic || ''))}`, cursor, size),
    },
  },
  notifications: {
    base: '/api/notifications/2025-09-14',
    page: (cursor, size = 25) => `/api/notifications/2026-07-26?size=${encodeURIComponent(size)}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`,
    markAllRead: '/api/notifications/2026-07-26/read-all',
    unreadCount: '/api/notifications/2025-09-14/unread-count',
    preferences: '/api/notifications/2025-09-14/preferences',
    markRead: (id) => `/api/notifications/2025-09-14/${encodeURIComponent(id)}/read`,
  },
  sharedFolder: {
    folders: '/api/shared-folder/2026-07-17/folders',
    rename: '/api/shared-folder/2026-07-17/entries/rename',
    move: '/api/shared-folder/2026-07-17/entries/move',
    delete: '/api/shared-folder/2026-07-17/entries',
    uploads: '/api/shared-folder/2026-07-17/uploads',
    uploadStatus: (id) => `/api/shared-folder/2026-07-17/uploads/${encodeURIComponent(id)}`,
    uploadChunk: (id, offset) =>
      `/api/shared-folder/2026-07-17/uploads/${encodeURIComponent(id)}/chunks/${encodeURIComponent(offset)}`,
    uploadComplete: (id) =>
      `/api/shared-folder/2026-07-17/uploads/${encodeURIComponent(id)}/complete`,
    uploadCancel: (id) => `/api/shared-folder/2026-07-17/uploads/${encodeURIComponent(id)}`,
    entries: (path = '') => {
      const params = new URLSearchParams({ path: String(path || '') });
      return `/api/shared-folder/2026-07-17/entries?${params}`;
    },
    search: (query) => {
      const params = new URLSearchParams({ query: String(query ?? '') });
      return `/api/shared-folder/2026-07-17/search?${params}`;
    },
    content: (path) => {
      const params = new URLSearchParams({ path: String(path || '') });
      return `/api/shared-folder/2026-07-17/content?${params}`;
    },
    preview: (path) => {
      const params = new URLSearchParams({ path: String(path || '') });
      return `/api/shared-folder/2026-07-17/preview?${params}`;
    },
    radio: {
      playback: '/api/shared-folder/2026-07-17/radio',
      duration: '/api/shared-folder/2026-07-17/radio/duration',
    },
    media: {
      playback: '/api/shared-folder/2026-07-17/media/playback',
      fallback: '/api/shared-folder/2026-07-17/media/fallback',
      job: (id) => `/api/shared-folder/2026-07-17/media/jobs/${encodeURIComponent(id)}`,
      cancel: (id) => `/api/shared-folder/2026-07-17/media/jobs/${encodeURIComponent(id)}`,
      stream: (id) => `/api/shared-folder/2026-07-17/media/jobs/${encodeURIComponent(id)}/stream`,
    },
    admin: {
      audit: (filters = {}) => {
        const params = new URLSearchParams();
        Object.entries(filters).forEach(([key, value]) => {
          if (value !== undefined && value !== null && String(value) !== '') {
            params.set(key, String(value));
          }
        });
        const query = params.toString();
        return `/api/shared-folder/2026-07-17/admin/audit${query ? `?${query}` : ''}`;
      },
      recycle: (page = 0) => `/api/shared-folder/2026-07-17/admin/recycle?page=${encodeURIComponent(page)}`,
      restore: (id) => `/api/shared-folder/2026-07-17/admin/recycle/${encodeURIComponent(id)}/restore`,
      purge: (id) => `/api/shared-folder/2026-07-17/admin/recycle/${encodeURIComponent(id)}`,
    },
  },
  music: {
    access: '/api/music/2026-07-28/access',
    catalog: ({
      q = '', artist = '', album = '', genre = '', page = 0, size = 50,
      favorite = false, playlistId = '',
    } = {}) => {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (q) params.set('q', String(q));
      if (artist) params.set('artist', String(artist));
      if (album) params.set('album', String(album));
      if (genre) params.set('genre', String(genre));
      if (favorite) params.set('favorite', 'true');
      if (playlistId) params.set('playlistId', String(playlistId));
      return `/api/music/2026-07-28/catalog?${params}`;
    },
    stream: id => `/api/music/2026-07-28/tracks/${encodeURIComponent(id)}/stream`,
    artwork: id => `/api/music/2026-07-28/tracks/${encodeURIComponent(id)}/artwork`,
    radio: '/api/music/2026-07-28/radio',
    queue: '/api/music/2026-07-28/queue',
    queueItem: id => `/api/music/2026-07-28/queue/${encodeURIComponent(id)}`,
    metadata: id => `/api/music/2026-07-28/tracks/${encodeURIComponent(id)}/metadata`,
    metadataUndo: id => `/api/music/2026-07-28/metadata-edits/${encodeURIComponent(id)}/undo`,
    library: {
      playlists: '/api/music/2026-07-28/library/playlists',
      playlist: id => `/api/music/2026-07-28/library/playlists/${encodeURIComponent(id)}`,
      preferences: id =>
        `/api/music/2026-07-28/library/tracks/${encodeURIComponent(id)}/preferences`,
      history: (limit = 50) =>
        `/api/music/2026-07-28/library/history?limit=${encodeURIComponent(limit)}`,
    },
    admin: {
      accessAttempts: (limit = 100) =>
        `/api/music/2026-07-28/admin/access-attempts?limit=${encodeURIComponent(limit)}`,
    },
  },
  location: {
    zipCoordinate: (zipCode) => `/api/location/zip/${encodeURIComponent(zipCode)}`,
    importCensusZipCoordinates: '/api/location/zip/import/census',
  },
  messages: {
    base: '/api/messages/2025-09-14',
    conversations: '/api/messages/2025-09-14/conversations',
    conversation: (username) => `/api/messages/2025-09-14/conversation/${encodeURIComponent(username)}`,
    conversationPage: (username, cursor = null, size = 50) => {
      const params = new URLSearchParams({ size: String(size) });
      if (cursor) params.set('cursor', String(cursor));
      return `/api/messages/2026-07-26/conversation/${encodeURIComponent(username)}?${params}`;
    },
    archiveConversation: (username) =>
      `/api/messages/2026-07-26/conversation/${encodeURIComponent(username)}/archive`,
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
    importOpenStreetMapPreview: '/api/whatsforlunch/restaurant/2026-07-26/import/openstreetmap/preview',
    importOpenStreetMapApply: '/api/whatsforlunch/restaurant/2026-07-26/import/openstreetmap/apply',
    importOpenStreetMapStatus: '/api/whatsforlunch/restaurant/2026-07-26/import/openstreetmap/status',
    dedupeNamesPreview: '/api/whatsforlunch/restaurant/2026-07-26/dedupe-names/preview',
    dedupeNamesApply: '/api/whatsforlunch/restaurant/2026-07-26/dedupe-names/apply',
    freshness: '/api/whatsforlunch/restaurant/2026-07-26/freshness',
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
  photos: {
    images: '/api/photo/v1',
  },
};
