/**
 * Navigation web component.
 *
 * Renders the responsive navbar, adapts to auth state, and provides
 * a minimal JS fallback for the mobile hamburger when Bootstrap JS
 * is not present on the page.
 */
import pubsub from './pubsub.js';
import { API } from '../lib/api.js';
import { authHeaders, fetchJson, formatWhen, getAuthToken, loginRedirectUrl, sanitize } from '../lib/util.js';
import {
    browserNotificationsToShow,
    notificationTargetUrl,
    notificationText,
    notificationTitle,
    recentNotifications
} from '../lib/notifications.js';

const BROWSER_NOTIFICATION_SEEN_KEY = 'cbellBrowserNotificationSeenIds';
const BROWSER_NOTIFICATION_SEEDED_KEY = 'cbellBrowserNotificationSeenSeeded';

function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text || '';
    return div.innerHTML;
}

/** Resolve the Messages nav target while preserving post-login return behavior. */
export function messagesNavHref(isAuthenticated) {
    return isAuthenticated ? '/messages' : loginRedirectUrl('/messages');
}

/** Secondary tools shown under the Tools dropdown. */
export function toolsMenuItems() {
    return [
        { href: '/canes-box-tracker', label: 'Raising Canes Box Index' },
        { href: '/vin-decoder', label: 'VIN Decoder' },
        { href: '/wfl', label: "What's For Lunch" },
        { href: '/zip-coordinates', label: 'ZIP Coordinates' },
    ];
}

/** Primary nav destinations shown directly in the console rail. */
export function topLevelNavItems(isAuthenticated) {
    return [
        { href: '/void', label: 'Feed' },
        { href: messagesNavHref(isAuthenticated), label: 'Messages' },
    ];
}

/** Administrative destinations shown only for an exact stored ADMIN role. */
export function adminMenuItems(isAdmin) {
    return isAdmin ? [
        { href: '/back-office', label: 'Back Office' },
        { href: '/command-center', label: 'Command Center' },
    ] : [];
}

/** Determine whether a nav href represents the current browser route. */
export function isActiveNavHref(href, pathname = window.location.pathname) {
    const currentPath = String(pathname || '/').replace(/\/+$/, '') || '/';
    const targetPath = String(href || '').split('?')[0].replace(/\/+$/, '') || '/';
    if (targetPath === '/void') {
        return currentPath === '/void' || currentPath.startsWith('/p/');
    }
    if (targetPath === '/messages') {
        return currentPath === '/messages';
    }
    return currentPath === targetPath || currentPath.startsWith(`${targetPath}/`);
}

function activeClass(href, pathname = window.location.pathname) {
    return isActiveNavHref(href, pathname) ? ' active' : '';
}

function ariaCurrent(href, pathname = window.location.pathname) {
    return isActiveNavHref(href, pathname) ? ' aria-current="page"' : '';
}

/** Close a nav-controlled panel and reset the trigger's expanded state. */
export function hideNavPanel(panel, trigger, openClass = 'show', hiddenClass = '') {
    if (panel) {
        if (hiddenClass) {
            panel.classList.add(hiddenClass);
        }
        panel.classList.remove(openClass);
    }
    if (trigger) {
        trigger.setAttribute('aria-expanded', 'false');
    }
}

class AppNav extends HTMLElement {
    /** Lifecycle hook: mount component and subscribe to auth changes. */
    connectedCallback() {
        this.notifications = this.notifications || [];
        this.notificationPreferences = this.notificationPreferences || null;
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
            this.notificationPreferences = null;
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
        const token = getAuthToken();
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
        const token = getAuthToken();
        if (!token || this.notificationLoadInFlight) return;
        this.notificationLoadInFlight = true;
        try {
            const [items, unread] = await Promise.all([
                fetchJson(`${API.notifications.base}?limit=10`, { headers: authHeaders() }),
                fetchJson(API.notifications.unreadCount, { headers: authHeaders() }),
            ]);
            this.notifications = Array.isArray(items) ? items : [];
            this.notificationPreferences = await this.loadNotificationPreferences();
            this.unreadNotifications = Number(unread || 0);
            this.showBrowserNotifications(this.notifications);
            this.render();
        } catch (_) {
            // Notifications are additive UI; keep the nav usable if loading fails.
        } finally {
            this.notificationLoadInFlight = false;
        }
    }

    async loadNotificationPreferences() {
        try {
            return await fetchJson(API.notifications.preferences, { headers: authHeaders() });
        } catch (_) {
            return null;
        }
    }

    notificationPermissionStatus() {
        if (typeof window === 'undefined' || !('Notification' in window)) {
            return 'unsupported';
        }
        return window.Notification.permission;
    }

    notificationPermissionHtml() {
        const status = this.notificationPermissionStatus();
        if (status === 'unsupported') {
            return '';
        }
        if (status === 'granted') {
            return '<span class="notification-browser-status">Browser alerts on</span>';
        }
        if (status === 'denied') {
            return '<span class="notification-browser-status">Browser alerts blocked</span>';
        }
        return '<button type="button" class="notification-enable-btn">Enable browser alerts</button>';
    }

    seenBrowserNotificationIds() {
        try {
            return new Set(JSON.parse(localStorage.getItem(BROWSER_NOTIFICATION_SEEN_KEY) || '[]'));
        } catch (_) {
            return new Set();
        }
    }

    saveSeenBrowserNotificationIds(ids) {
        localStorage.setItem(BROWSER_NOTIFICATION_SEEN_KEY, JSON.stringify([...ids].slice(-100)));
    }

    seedBrowserNotificationIds(notifications) {
        const ids = new Set((notifications || []).map(notification => notification.id).filter(Boolean));
        this.saveSeenBrowserNotificationIds(ids);
        localStorage.setItem(BROWSER_NOTIFICATION_SEEDED_KEY, 'true');
    }

    showBrowserNotifications(notifications) {
        if (this.notificationPermissionStatus() !== 'granted') {
            return;
        }
        if (localStorage.getItem(BROWSER_NOTIFICATION_SEEDED_KEY) !== 'true') {
            this.seedBrowserNotificationIds(notifications);
            return;
        }

        const seenIds = this.seenBrowserNotificationIds();
        const toShow = browserNotificationsToShow(notifications, seenIds, this.notificationPreferences);
        toShow.forEach(notification => {
            seenIds.add(notification.id);
            const alert = new window.Notification(notificationTitle(notification), {
                body: notificationText(notification),
                tag: notification.id,
            });
            alert.onclick = () => {
                window.focus?.();
                window.location.href = notificationTargetUrl(notification);
                alert.close?.();
            };
        });
        this.saveSeenBrowserNotificationIds(seenIds);
    }

    notificationItemsHtml() {
        const notifications = recentNotifications(this.notifications || []);
        if (notifications.length === 0) {
            return '<div class="notification-empty text-muted">No notifications</div>';
        }
        return notifications.map(notification => {
            const unread = !notification.read;
            const text = escapeHtml(notificationText(notification));
            const title = escapeHtml(notificationTitle(notification));
            const targetUrl = sanitize(notificationTargetUrl(notification));
            return `
                <button type="button" class="notification-item ${unread ? 'unread' : ''}" data-notification-id="${sanitize(notification.id || '')}" data-target-url="${targetUrl}">
                    <span class="notification-title">${title}</span>
                    <span class="notification-text">${text}</span>
                    <span class="notification-time">${formatWhen(notification.createdOn)}</span>
                </button>`;
        }).join('');
    }

    /** Render the navbar markup based on authentication state. */
    render() {
        const isAuthenticated = !!getAuthToken();
        const storedName = (localStorage.getItem('cbellUsername') || '').trim();
        const initials = storedName ? storedName[0].toUpperCase() : 'C';
        const loginHref = loginRedirectUrl();
        const messagesHref = messagesNavHref(isAuthenticated);
        const profileHref = isAuthenticated ? '/profile' : loginHref;
        const isAdmin = (localStorage.getItem('cbellRole') || '') === 'ADMIN';
        const unread = Number(this.unreadNotifications || 0);
        const currentPath = window.location.pathname;
        const toolsActive = toolsMenuItems().some(item => isActiveNavHref(item.href, currentPath));
        this.innerHTML = `
<nav class="navbar navbar-expand-lg navbar-dark void-console-nav">
    <div class="void-nav-status-row">
        <span class="void-signal-online"><span class="void-signal-dot" aria-hidden="true"></span>Signal Online</span>
        <span class="void-channel-label">Void channel / public</span>
    </div>
    <div class="container-fluid void-nav-row">
        <a href="/" class="navbar-brand void-nav-brand" aria-label="Void home">
            <span class="void-brand-mark" aria-hidden="true">V</span>
            <span>Void</span>
        </a>
        <button class="navbar-toggler" type="button" data-bs-toggle="collapse" data-bs-target="#navbarSupportedContent" aria-controls="navbarSupportedContent" aria-expanded="false" aria-label="Toggle navigation">
            <span class="navbar-toggler-icon"></span>
        </button>
        <div class="navbar-collapse collapse" id="navbarSupportedContent">
            <ul class="navbar-nav me-auto mb-2 mb-lg-0">
                ${topLevelNavItems(isAuthenticated).map(item => `<li class="nav-item"><a href="${item.href}" class="nav-link${activeClass(item.href, currentPath)}"${ariaCurrent(item.href, currentPath)}>${item.label}</a></li>`).join('')}
                <li class="nav-item dropdown">
                    <a class="nav-link dropdown-toggle${toolsActive ? ' active' : ''}" href="#" id="toolsDropdown" role="button" data-bs-toggle="dropdown" aria-expanded="false"${toolsActive ? ' aria-current="page"' : ''}>Tools</a>
                    <ul class="dropdown-menu tools-menu" aria-labelledby="toolsDropdown">
                        ${toolsMenuItems().map(item => `<li><a class="dropdown-item${activeClass(item.href, currentPath)}" href="${item.href}"${ariaCurrent(item.href, currentPath)}>${item.label}</a></li>`).join('')}
                    </ul>
                </li>
            </ul>
            <div class="d-flex align-items-center gap-2 ms-auto">
                ${!isAuthenticated ? `
                <div class="d-lg-flex void-auth-actions">
                    <a href="${loginHref}" class="btn btn-outline-light me-2 void-login-action">Login</a>
                    <a href="/signup" class="btn btn-warning void-signup-action">Sign-up</a>
                </div>` : `
                <div class="nav-notifications">
                    <button type="button" class="notification-btn" aria-haspopup="true" aria-expanded="false" aria-label="Notifications">
                        <i class="fa fa-bell-o" aria-hidden="true"></i>
                        ${unread > 0 ? `<span class="notification-badge">${unread > 9 ? '9+' : unread}</span>` : ''}
                    </button>
                    <div class="notification-panel d-none">
                        <div class="notification-panel-title">
                            <span>Notifications</span>
                            <a href="/notifications">View all</a>
                        </div>
                        ${this.notificationPermissionHtml()}
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
                        ${adminMenuItems(isAdmin).map(item => `<a class="dropdown-item" href="${item.href}">${item.label}</a>`).join('')}
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
                const profileMenu = this.querySelector('.profile-menu');
                const avatarBtn = this.querySelector('.avatar-btn');
                hideNavPanel(profileMenu, avatarBtn);
                this.dropdownInstance?.hide?.();
                const isShown = !notificationPanel.classList.contains('d-none');
                notificationPanel.classList.toggle('d-none', isShown);
                notificationBtn.setAttribute('aria-expanded', String(!isShown));
            });
            this.querySelectorAll('.notification-item').forEach(item => {
                item.addEventListener('click', async (e) => {
                    e.preventDefault();
                    const notificationId = item.getAttribute('data-notification-id');
                    const targetUrl = item.getAttribute('data-target-url') || '/notifications';
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
                    window.location.href = targetUrl;
                });
            });
            const enableBrowserNotifications = this.querySelector('.notification-enable-btn');
            if (enableBrowserNotifications) {
                enableBrowserNotifications.addEventListener('click', async () => {
                    if (this.notificationPermissionStatus() !== 'default') return;
                    const result = await window.Notification.requestPermission();
                    if (result === 'granted') {
                        this.seedBrowserNotificationIds(this.notifications || []);
                    }
                    this.render();
                });
            }
            this.notificationOutsideClickHandler = (e) => {
                if (!this.contains(e.target)) {
                    hideNavPanel(notificationPanel, notificationBtn, 'show', 'd-none');
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
            avatarBtn.addEventListener('click', () => {
                const notificationPanel = this.querySelector('.notification-panel');
                const notificationBtn = this.querySelector('.notification-btn');
                hideNavPanel(notificationPanel, notificationBtn, 'show', 'd-none');
            });
        }
        if (avatarBtn && profileMenu && !hasBootstrap) {
            if (this.outsideClickHandler) {
                document.removeEventListener('click', this.outsideClickHandler);
            }
            avatarBtn.addEventListener('click', (e) => {
                e.preventDefault();
                const notificationPanel = this.querySelector('.notification-panel');
                const notificationBtn = this.querySelector('.notification-btn');
                hideNavPanel(notificationPanel, notificationBtn, 'show', 'd-none');
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
