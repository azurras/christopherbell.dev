# Blog

Owns read-only blog post serving.

## What Lives Here

- `BlogController` and `BlogService`, which list configured posts and find one by ID.
- `BlogConfiguration`, which registers `BlogProperties`.
- Blog DTO/model classes under `model`.
- Blog content is configured from application properties rather than authored through an admin UI.
- An empty configured post list renders a clear public empty state instead of placeholder content.
- `GET /api/blog/v1/posts` and `GET /api/blog/v1/posts/{id}` are anonymous read APIs; both return the standard `Response<BlogResponse>` envelope.

## Package Shape

This package intentionally stays flat while it only owns blog list/detail reads.
Create a `content` subpackage if authoring, indexing, or multiple content
providers are added.

## Update This Doc

Update this README when blog source data, routing, response fields, or rendering assumptions change.
