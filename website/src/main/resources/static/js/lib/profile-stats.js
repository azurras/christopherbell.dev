/** Normalize public profile activity stats for UI rendering. */
export function profileActivityStats(profile) {
  return {
    postCount: safeCount(profile?.postCount),
    replyCount: safeCount(profile?.replyCount),
    followerCount: safeCount(profile?.followerCount),
    followingCount: safeCount(profile?.followingCount)
  };
}

function safeCount(value) {
  const count = Number(value ?? 0);
  return Number.isFinite(count) && count > 0 ? count : 0;
}
