/**
 * Navigation web component.
 *
 * Renders the responsive navbar, adapts to auth state, and provides
 * a minimal JS fallback for the mobile hamburger when Bootstrap JS
 * is not present on the page.
 */
import pubsub from './pubsub.js';

class AppNav extends HTMLElement {
    /** Lifecycle hook: mount component and subscribe to auth changes. */
    connectedCallback() {
        this.render();
        this.loadUserInfo();
        pubsub.subscribe('auth:login', async () => {
            await this.loadUserInfo(true);
            this.render();
        });
        pubsub.subscribe('auth:logout', () => {
            localStorage.removeItem('cbellUsername');
            localStorage.removeItem('cbellRole');
            this.render();
        });
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

    /** Render the navbar markup based on authentication state. */
    render() {
        const isAuthenticated = !!localStorage.getItem('cbellLoginToken');
        const storedName = (localStorage.getItem('cbellUsername') || '').trim();
        const initials = storedName ? storedName[0].toUpperCase() : 'C';
        const profileHref = isAuthenticated ? '/profile' : '/login';
        const isAdmin = (localStorage.getItem('cbellRole') || '') === 'ADMIN';
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
                <li class="nav-item"><a href="/wfl" class="nav-link">What's For Lunch</a></li>
            </ul>
            <div class="d-flex align-items-center gap-2 ms-auto">
                ${!isAuthenticated ? `
                <div class="d-lg-flex">
                    <a href="/login" class="btn btn-outline-light me-2">Login</a>
                    <a href="/signup" class="btn btn-warning">Sign-up</a>
                </div>` : `
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

        const avatarBtn = this.querySelector('.avatar-btn');
        const profileMenu = this.querySelector('.profile-menu');
        const hasBootstrap = typeof window !== 'undefined' && window.bootstrap && window.bootstrap.Dropdown;
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
