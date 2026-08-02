/**
 * App bootstrap script.
 *
 * Responsibilities:
 * - Mount header and footer web components
 * - Mount optional page widgets (blog, gallery) when containers exist
 * - Handle auth logout pub/sub to clear token and redirect to login
 */
import './components/nav.js';
import './components/footer.js';
import pubsub from './components/pubsub.js';
import { API } from './lib/api.js';
import { clearAuthState, fetchJson } from './lib/util.js';
import { mountLazyComponent } from './lib/lazy-component.js';
import { resumeSiteMediaIfPresent, stopSiteMediaPlayback } from './lib/site-media-loader.js';

/** Wire core layout and global auth behavior once DOM is ready. */
async function bootstrapApp() {
    const navContainer = document.getElementById('nav');
    if (navContainer) {
        navContainer.appendChild(document.createElement('app-nav'));
    }

    const footerContainer = document.getElementById('footer');
    if (footerContainer) {
        footerContainer.appendChild(document.createElement('app-footer'));
    }

    pubsub.subscribe('auth:logout', async () => {
        const siteWindow = window.top || window;
        try {
            await fetchJson(API.accounts.logout, { method: 'POST' });
        } finally {
            await stopSiteMediaPlayback();
            clearAuthState();
            siteWindow.location.href = '/login';
        }
    });

    await Promise.all([
        mountLazyComponent(
            document.getElementById('blog'),
            'blog-posts',
            () => import('./components/blog.js'),
        ),
        mountLazyComponent(
            document.getElementById('gallery'),
            'photo-gallery',
            () => import('./components/gallery.js'),
        ),
        resumeSiteMediaIfPresent(),
    ]);
}

document.addEventListener('DOMContentLoaded', () => {
    void bootstrapApp().catch(error => console.error('App bootstrap failed.', error));
});
