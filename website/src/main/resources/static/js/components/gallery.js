/**
 * Public photo-gallery component.
 *
 * Fetches configured image metadata and renders content images with meaningful alternatives.
 */
import { API } from '../lib/api.js';
import { fetchJson } from '../lib/util.js';

/** Return a stable owned image collection from the standard API envelope. */
export function galleryImagesFromResponse(response) {
    const images = response?.payload?.images ?? response?.images;
    return Array.isArray(images) ? [...images] : [];
}

/** Content images prefer their description, then name, then an honest generic fallback. */
export function galleryAltText(image) {
    const description = String(image?.description || '').trim();
    const usableDescription = /^n\/a$/i.test(description) ? '' : description;
    return usableDescription || String(image?.name || '').trim() || 'Gallery photo';
}

class PhotoGallery extends HTMLElement {
    constructor() {
        super();
        this.images = [];
    }

    connectedCallback() {
        this.render();
        this.loadImages();
    }

    async loadImages() {
        try {
            this.images = galleryImagesFromResponse(await fetchJson(API.photos.images));
            this.update();
        } catch (error) {
            console.error('Failed to load gallery images', error);
        }
    }

    update() {
        const row = this.querySelector('.gallery-row');
        row.replaceChildren();
        for (const image of this.images) {
            const col = document.createElement('div');
            col.className = 'col';
            const img = document.createElement('img');
            img.src = String(image?.path || '');
            img.className = 'img-fluid rounded';
            img.alt = galleryAltText(image);
            col.appendChild(img);
            row.appendChild(col);
        }
    }

    render() {
        const container = document.createElement('div');
        container.className = 'container-fluid';
        const row = document.createElement('div');
        row.className = 'row row-cols-1 row-cols-sm-1 row-cols-md-2 g-2 gallery-row';
        container.appendChild(row);
        this.replaceChildren(container);
    }
}

customElements.define('photo-gallery', PhotoGallery);
