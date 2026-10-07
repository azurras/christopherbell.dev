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

function appendTextElement(parentElement, tagName, className, text) {
    const textElement = document.createElement(tagName);
    if (className) textElement.className = className;
    textElement.textContent = String(text || '');
    parentElement.appendChild(textElement);
    return textElement;
}

class BlogPosts extends HTMLElement {
    connectedCallback() {
        this.renderEmptyContainer();
        this.loadPosts();
    }

    async loadPosts() {
        try {
            const postsResponse = await fetchJson(API.blog.posts);
            this.renderPosts(blogPostsFromResponse(postsResponse));
        } catch (error) {
            console.error('Failed to load posts', error);
        }
    }

    renderPosts(blogPosts) {
        const postsContainer = this.querySelector('.blogPosts');
        postsContainer.replaceChildren();

        if (blogPosts.length === 0) {
            appendTextElement(
                postsContainer,
                'p',
                'text-center blog-empty-state',
                'No posts have been published yet.'
            );
            return;
        }

        for (const blogPost of blogPosts) {
            postsContainer.appendChild(articleFor(blogPost));
        }
    }

    renderEmptyContainer() {
        const postsContainer = document.createElement('div');
        postsContainer.className = 'blogPosts';
        this.replaceChildren(postsContainer);
    }
}

function articleFor(blogPost) {
    const article = document.createElement('article');
    article.className = 'blogArticle';
    appendTextElement(article, 'h2', 'text-center', blogPost?.title);
    appendTextElement(article, 'h5', 'text-center', `Author: ${blogPost?.author || 'Unknown'}`);
    const publishedOn = blogPost?.createdOn ? new Date(blogPost.createdOn) : null;
    if (publishedOn && !Number.isNaN(publishedOn.getTime())) {
        const publishedOnElement = appendTextElement(
            article,
            'time',
            'd-block text-center',
            publishedOn.toLocaleDateString()
        );
        publishedOnElement.dateTime = publishedOn.toISOString();
    }
    article.appendChild(document.createElement('hr'));
    appendTextElement(article, 'pre', '', blogPost?.contentText);
    return article;
}

customElements.define('blog-posts', BlogPosts);
