/**
 * Public blog-post component.
 *
 * Fetches the versioned read API once and renders configured post text without interpreting HTML.
 */
import { API } from '../lib/api.js';
import { fetchJson } from '../lib/util.js';

/** Return a stable owned post collection from the standard API envelope. */
export function blogPostsFromResponse(response) {
    const posts = response?.payload?.posts ?? response?.posts;
    return Array.isArray(posts) ? [...posts] : [];
}

function appendTextElement(parent, tagName, className, text) {
    const element = document.createElement(tagName);
    if (className) element.className = className;
    element.textContent = String(text || '');
    parent.appendChild(element);
    return element;
}

class BlogPosts extends HTMLElement {
    constructor() {
        super();
        this.posts = [];
    }

    connectedCallback() {
        this.render();
        this.loadPosts();
    }

    async loadPosts() {
        try {
            this.posts = blogPostsFromResponse(await fetchJson(API.blog.posts));
            this.updatePosts();
        } catch (error) {
            console.error('Failed to load posts', error);
        }
    }

    updatePosts() {
        const postsContainer = this.querySelector('.blogPosts');
        postsContainer.replaceChildren();

        for (const post of this.posts) {
            const article = document.createElement('article');
            article.className = 'blogArticle';
            appendTextElement(article, 'h2', 'text-center', post?.title);
            appendTextElement(article, 'h5', 'text-center', `Author: ${post?.author || 'Unknown'}`);
            if (post?.createdOn) {
                const createdOn = new Date(post.createdOn);
                if (!Number.isNaN(createdOn.getTime())) {
                    const time = appendTextElement(
                        article,
                        'time',
                        'd-block text-center',
                        createdOn.toLocaleDateString()
                    );
                    time.dateTime = createdOn.toISOString();
                }
            }
            article.appendChild(document.createElement('hr'));
            appendTextElement(article, 'pre', '', post?.contentText);
            postsContainer.appendChild(article);
        }
    }

    render() {
        const postsContainer = document.createElement('div');
        postsContainer.className = 'blogPosts';
        this.replaceChildren(postsContainer);
    }
}

customElements.define('blog-posts', BlogPosts);
