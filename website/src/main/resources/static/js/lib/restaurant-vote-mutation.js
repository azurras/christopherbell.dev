/**
 * Owns one restaurant's browser vote mutation sequence so stale responses cannot
 * replace the newest server-confirmed vote state.
 */
export function createRestaurantVoteMutation({ buttons, apply, showError }) {
  let generation = 0;

  return async function mutate(request, controls = buttons()) {
    const requestGeneration = ++generation;
    controls.forEach(button => { button.disabled = true; });
    try {
      const value = await request();
      if (requestGeneration === generation) apply(value);
    } catch (error) {
      if (requestGeneration === generation) showError(error);
    } finally {
      if (requestGeneration === generation) {
        controls.forEach(button => { button.disabled = false; });
      }
    }
  };
}
