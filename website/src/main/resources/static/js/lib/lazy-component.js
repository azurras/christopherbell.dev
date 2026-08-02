/** Load and mount an optional custom element only when its page host exists. */
export async function mountLazyComponent(host, tagName, loadDefinition) {
  if (!host) return false;
  await loadDefinition();
  host.appendChild(host.ownerDocument.createElement(tagName));
  return true;
}
