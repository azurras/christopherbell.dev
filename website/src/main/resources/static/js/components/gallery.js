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
    connectedCallback() {
        this.renderEmptyGallery();
        this.loadGallery();
    }

    async loadGallery() {
        try {
            const galleryResponse = await fetchJson(API.photos.images);
            this.renderGallery(galleryImagesFromResponse(galleryResponse));
        } catch (error) {
            console.error('Failed to load gallery images', error);
        }
    }

    renderGallery(galleryImages) {
        const galleryRow = this.querySelector('.gallery-row');
        galleryRow.replaceChildren();
        for (const galleryImage of galleryImages) {
            const imageColumn = document.createElement('div');
            imageColumn.className = 'col';
            const imageElement = document.createElement('img');
            imageElement.src = String(galleryImage?.path || '');
            imageElement.className = 'img-fluid rounded';
            imageElement.alt = galleryAltText(galleryImage);
            imageColumn.appendChild(imageElement);
            galleryRow.appendChild(imageColumn);
        }
    }

    renderEmptyGallery() {
        const galleryContainer = document.createElement('div');
        galleryContainer.className = 'container-fluid';
        const galleryRow = document.createElement('div');
        galleryRow.className = 'row row-cols-1 row-cols-sm-1 row-cols-md-2 g-2 gallery-row';
        galleryContainer.appendChild(galleryRow);
        this.replaceChildren(galleryContainer);
    }
}

customElements.define('photo-gallery', PhotoGallery);
