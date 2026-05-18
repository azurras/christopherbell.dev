/**
 * Navigation web component.
 *
 * Renders the responsive navbar, adapts to auth state, and provides
 * a minimal JS fallback for the mobile hamburger when Bootstrap JS
 * is not present on the page.
 */
import pubsub from './pubsub.js';
import { API } from '../lib/api.js';
import { authHeaders, fetchJson, formatWhen, sanitize } from '../lib/util.js';

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text || '';
    return div.innerHTML;
}

class AppNav extends HTMLElement {
    /** Lifecycle hook: mount component and subscribe to auth changes. */
    connectedCallback() {
        this.notifications = this.notifications || [];
        this.unreadNotifications = this.unreadNotifications || 0;
        this.render();
        this.loadUserInfo();
        this.loadNotifications();
        if (!this.notificationPoll) {
            this.notificationPoll = window.setInterval(() => this.loadNotifications(), 30000);
        }
        pubsub.subscribe('auth:login', async () => {
            await this.loadUserInfo(true);
            await this.loadNotifications();
            this.render();
        });
        pubsub.subscribe('auth:logout', () => {
            localStorage.removeItem('cbellUsername');
            localStorage.removeItem('cbellRole');
            this.notifications = [];
            this.unreadNotifications = 0;
            this.render();
        });
    }

    disconnectedCallback() {
        if (this.notificationPoll) {
            window.clearInterval(this.notificationPoll);
            this.notificationPoll = null;
        }
        if (this.notificationOutsideClickHandler) {
            document.removeEventListener('click', this.notificationOutsideClickHandler);
            this.notificationOutsideClickHandler = null;
        }
        if (this.toolsOutsideClickHandler) {
            document.removeEventListener('click', this.toolsOutsideClickHandler);
            this.toolsOutsideClickHandler = null;
        }
        if (this.toolsDropdownInstance) {
            this.toolsDropdownInstance.dispose();
            this.toolsDropdownInstance = null;
        }
        if (this.dropdownInstance) {
            this.dropdownInstance.dispose();
            this.dropdownInstance = null;
        }
    }

    async loadUserInfo(force = false) {
        const token = localStorage.getItem('cbellLoginToken');
        if (!token) return;
        if (!force && localStorage.getItem('cbellRole') && localStorage.getItem('cbellUsername')) {
            return;
        }
        if (this.userLoadInFlight) return;
        this.userLoadInFlight = true;
        try {
            const resp = await fetch('/api/accounts/2025-09-03/me', {
                headers: { Authorization: `Bearer ${token}` }
            });
            const data = await resp.json().catch(() => ({}));
            if (resp.ok && data && data.payload) {
                localStorage.setItem('cbellUsername', data.payload.username || '');
                localStorage.setItem('cbellRole', data.payload.role || '');
                this.render();
            }
        } catch (_) {
            // Ignore profile fetch errors to keep nav usable.
        } finally {
            this.userLoadInFlight = false;
        }
    }

    async loadNotifications() {
        const token = localStorage.getItem('cbellLoginToken');
        if (!token || this.notificationLoadInFlight) return;
        this.notificationLoadInFlight = true;
        try {
            const [items, unread] = await Promise.all([
                fetchJson(`${API.notifications.base}?limit=10`, { headers: authHeaders() }),
                fetchJson(API.notifications.unreadCount, { headers: authHeaders() }),
            ]);
            this.notifications = Array.isArray(items) ? items : [];
            this.unreadNotifications = Number(unread || 0);
            this.render();
        } catch (_) {
            // Notifications are additive UI; keep the nav usable if loading fails.
        } finally {
            this.notificationLoadInFlight = false;
        }
    }

    notificationItemsHtml() {
        const notifications = this.notifications || [];
        if (notifications.length === 0) {
            return '<div class="notification-empty text-muted">No notifications</div>';
        }
        return notifications.map(notification => {
            const unread = !notification.read;
            const actor = notification.actorUsername ? `@${sanitize(notification.actorUsername)}` : 'Someone';
            const isMessage = notification.notificationType === 'MESSAGE';
            const text = escapeHtml(isMessage ? notification.messageText || '' : notification.postText || '');
            const title = isMessage ? `${actor} sent you a message` : `${actor} mentioned you`;
            return `
                <button type="button" class="notification-item ${unread ? 'unread' : ''}" data-notification-id="${notification.id}" data-post-id="${notification.postId || ''}" data-message-username="${isMessage ? sanitize(notification.actorUsername || '') : ''}">
                    <span class="notification-title">${title}</span>
                    <span class="notification-text">${text}</span>
                    <span class="notification-time">${formatWhen(notification.createdOn)}</span>
                </button>`;
        }).join('');
    }

    /** Render the navbar markup based on authentication state. */
    render() {
        const isAuthenticated = !!localStorage.getItem('cbellLoginToken');
        const storedName = (localStorage.getItem('cbellUsername') || '').trim();
        const initials = storedName ? storedName[0].toUpperCase() : 'C';
        const profileHref = isAuthenticated ? '/profile' : '/login';
        const isAdmin = (localStorage.getItem('cbellRole') || '') === 'ADMIN';
        const unread = Number(this.unreadNotifications || 0);
        this.innerHTML = `
<nav class="navbar navbar-expand-lg navbar-dark bg-dark">
    <div class="container-fluid">
        <a href="/" class="navbar-brand">Home</a>
        <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarSupportedContent" aria-controls="navbarSupportedContent" aria-expanded="false" aria-label="Toggle navigation">
            <span class="navbar-toggler-icon"></span>
        </button>
        <div class="navbar-collapse collapse" id="navbarSupportedContent">
            <ul class="navbar-nav me-auto mb-2 mb-lg-0">
                <li class="nav-item"><a href="/void" class="nav-link">Void</a></li>
                ${isAuthenticated ? `<li class="nav-item"><a href="/messages" class="nav-link">Messages</a></li>` : ''}
                <li class="nav-item dropdown">
                    <a class="nav-link dropdown-toggle" href="#" id="toolsDropdown" role="button" data-bs-toggle="dropdown" aria-expanded="false">Tools</a>
                    <ul class="dropdown-menu tools-menu" aria-labelledby="toolsDropdown">
                        <li><a class="dropdown-item" href="/wfl">What's For Lunch</a></li>
                        <li><a class="dropdown-item" href="/vin-decoder">VIN Decoder</a></li>
                    </ul>
                </li>
            </ul>
            <div class="d-flex align-items-center gap-2 ms-auto">
                ${!isAuthenticated ? `
                <div class="d-lg-flex">
                    <a href="/login" class="btn btn-outline-light me-2">Login</a>
                    <a href="/signup" class="btn btn-warning">Sign-up</a>
                </div>` : `
                <div class="nav-notifications">
                    <button type="button" class="notification-btn" aria-haspopup="true" aria-expanded="false" aria-label="Notifications">
                        <i class="fa fa-bell-o" aria-hidden="true"></i>
                        ${unread > 0 ? `<span class="notification-badge">${unread > 9 ? '9+' : unread}</span>` : ''}
                    </button>
                    <div class="notification-panel d-none">
                        <div class="notification-panel-title">Notifications</div>
                        <div class="notification-list">
                            ${this.notificationItemsHtml()}
                        </div>
                    </div>
                </div>
                <div class="nav-profile dropdown">
                    <button type="button" class="avatar-btn" aria-haspopup="true" aria-expanded="false" data-bs-toggle="dropdown" data-bs-display="static">
                        <span class="avatar-initials">${initials}</span>
                    </button>
                    <div class="dropdown-menu dropdown-menu-end profile-menu">
                        ${isAdmin ? `<a class="dropdown-item" href="/back-office">Back Office</a>` : ''}
                        <a class="dropdown-item" href="${profileHref}">Profile</a>
                        <button id="logout" type="button" class="dropdown-item">Logout</button>
                    </div>
                </div>`}
            </div>
        </div>
    </div>
</nav>`;
        const logoutBtn = this.querySelector('#logout');
        if (logoutBtn) {
            logoutBtn.addEventListener('click', () => {
                pubsub.publish('auth:logout');
            });
        }

        const notificationBtn = this.querySelector('.notification-btn');
        const notificationPanel = this.querySelector('.notification-panel');
        if (this.notificationOutsideClickHandler) {
            document.removeEventListener('click', this.notificationOutsideClickHandler);
            this.notificationOutsideClickHandler = null;
        }
        if (notificationBtn && notificationPanel) {
            notificationBtn.addEventListener('click', (e) => {
                e.preventDefault();
                e.stopPropagation();
                const isShown = !notificationPanel.classList.contains('d-none');
                notificationPanel.classList.toggle('d-none', isShown);
                notificationBtn.setAttribute('aria-expanded', String(!isShown));
            });
            this.querySelectorAll('.notification-item').forEach(item => {
                item.addEventListener('click', async (e) => {
                    e.preventDefault();
                    const notificationId = item.getAttribute('data-notification-id');
                    const postId = item.getAttribute('data-post-id');
                    const messageUsername = item.getAttribute('data-message-username');
                    if (notificationId) {
                        try {
                            await fetchJson(API.notifications.markRead(notificationId), {
                                method: 'POST',
                                headers: authHeaders(),
                            });
                        } catch (_) {
                            // Still allow navigation to the mentioned post.
                        }
                    }
                    if (messageUsername) {
                        window.location.href = `/messages?with=${encodeURIComponent(messageUsername)}`;
                        return;
                    }
                    if (postId) window.location.href = `/p/${encodeURIComponent(postId)}`;
                });
            });
            this.notificationOutsideClickHandler = (e) => {
                if (!this.contains(e.target)) {
                    notificationPanel.classList.add('d-none');
                    notificationBtn.setAttribute('aria-expanded', 'false');
                }
            };
            document.addEventListener('click', this.notificationOutsideClickHandler);
        }

        const hasBootstrap = typeof window !== 'undefined' && window.bootstrap && window.bootstrap.Dropdown;
        const toolsBtn = this.querySelector('#toolsDropdown');
        const toolsMenu = this.querySelector('.tools-menu');
        if (this.toolsOutsideClickHandler) {
            document.removeEventListener('click', this.toolsOutsideClickHandler);
            this.toolsOutsideClickHandler = null;
        }
        if (toolsBtn && toolsMenu && hasBootstrap) {
            if (this.toolsDropdownInstance) {
                this.toolsDropdownInstance.dispose();
            }
            this.toolsDropdownInstance = new window.bootstrap.Dropdown(toolsBtn);
        }
        if (toolsBtn && toolsMenu && !hasBootstrap) {
            if (this.toolsOutsideClickHandler) {
                document.removeEventListener('click', this.toolsOutsideClickHandler);
            }
            toolsBtn.addEventListener('click', (e) => {
                e.preventDefault();
                const isShown = toolsMenu.classList.contains('show');
                toolsMenu.classList.toggle('show', !isShown);
                toolsBtn.setAttribute('aria-expanded', String(!isShown));
            });
            this.toolsOutsideClickHandler = (e) => {
                if (!this.contains(e.target)) {
                    toolsMenu.classList.remove('show');
                    toolsBtn.setAttribute('aria-expanded', 'false');
                }
            };
            document.addEventListener('click', this.toolsOutsideClickHandler);
        }

        const avatarBtn = this.querySelector('.avatar-btn');
        const profileMenu = this.querySelector('.profile-menu');
        if (avatarBtn && profileMenu && hasBootstrap) {
            if (this.dropdownInstance) {
                this.dropdownInstance.dispose();
            }
            this.dropdownInstance = new window.bootstrap.Dropdown(avatarBtn);
        }
        if (avatarBtn && profileMenu && !hasBootstrap) {
            if (this.outsideClickHandler) {
                document.removeEventListener('click', this.outsideClickHandler);
            }
            avatarBtn.addEventListener('click', (e) => {
                e.preventDefault();
                const isShown = profileMenu.classList.contains('show');
                profileMenu.classList.toggle('show', !isShown);
                avatarBtn.setAttribute('aria-expanded', String(!isShown));
            });
            this.outsideClickHandler = (e) => {
                if (!this.contains(e.target)) {
                    profileMenu.classList.remove('show');
                    avatarBtn.setAttribute('aria-expanded', 'false');
                }
            };
            document.addEventListener('click', this.outsideClickHandler);
        }

        // Mobile toggler: if Bootstrap JS is present, let it handle.
        // Otherwise, add a minimal vanilla fallback toggle.
        const toggler = this.querySelector('.navbar-toggler');
        const target = this.querySelector('#navbarSupportedContent');
        const hasBootstrapCollapse = typeof window !== 'undefined' && window.bootstrap && window.bootstrap.Collapse;
        if (toggler && target && !hasBootstrapCollapse) {
            toggler.addEventListener('click', (e) => {
                e.preventDefault();
                const isShown = target.classList.contains('show');
                if (isShown) {
                    target.classList.remove('show');
                    toggler.setAttribute('aria-expanded', 'false');
                } else {
                    target.classList.add('show');
                    toggler.setAttribute('aria-expanded', 'true');
                }
            });
        }
    }
}

customElements.define('app-nav', AppNav);
