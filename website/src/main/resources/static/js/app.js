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
import './components/blog.js';
import './components/gallery.js';
import './components/site-media-player.js';
import pubsub from './components/pubsub.js';
import { API } from './lib/api.js';
import { clearAuthState, fetchJson } from './lib/util.js';
import {
    handleSiteNavigationClick,
    siteMediaPlayerHost,
    stopSiteMediaPlayback,
} from './lib/site-media-player.js';

/** Wire core layout and global auth behavior once DOM is ready. */
document.addEventListener('DOMContentLoaded', () => {
    if (window.top === window && !siteMediaPlayerHost()) {
        document.body.appendChild(document.createElement('site-media-player'));
    }

    const navContainer = document.getElementById('nav');
    if (navContainer) {
        navContainer.appendChild(document.createElement('app-nav'));
    }

    const footerContainer = document.getElementById('footer');
    if (footerContainer) {
        footerContainer.appendChild(document.createElement('app-footer'));
    }

    const blogContainer = document.getElementById('blog');
    if (blogContainer) {
        blogContainer.appendChild(document.createElement('blog-posts'));
    }

    const galleryContainer = document.getElementById('gallery');
    if (galleryContainer) {
        galleryContainer.appendChild(document.createElement('photo-gallery'));
    }

    pubsub.subscribe('auth:logout', async () => {
        const playerHost = siteMediaPlayerHost();
        try {
            await fetchJson(API.accounts.logout, { method: 'POST' });
        } finally {
            stopSiteMediaPlayback();
            clearAuthState();
            const siteWindow = playerHost?.ownerDocument?.defaultView || window;
            siteWindow.location.href = '/login';
        }
    });

});

document.addEventListener('click', event => {
    handleSiteNavigationClick(event);
}, true);
