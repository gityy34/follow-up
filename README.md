# Follow Up

Follow Up is a responsive website and installable progressive web app for tracking follow-ups. Add a title, due date, and optional contact and notes. Search your list, filter by due date or completion, and edit, complete, reopen, or delete entries.

## Run locally

From the repository root, run:

```sh
python3 -m http.server 8000 --directory dist
```

Open http://localhost:8000. The site has no build step or package dependencies.

## Hosting and installation

Publish the contents of `dist/` with any static host that serves HTTPS. After opening the site, use the browser's install option when available. On iOS, open the site in Safari and choose **Share → Add to Home Screen**. Once the service worker has cached the site, it can reopen offline.

Follow-ups are stored in the current browser profile. Clearing site data removes them. There is no account, device synchronization, or scheduled notification.

When changing the cached site files, increment the cache version in `dist/sw.js`.
