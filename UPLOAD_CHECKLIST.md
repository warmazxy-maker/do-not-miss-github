# GitHub Upload Checklist

## Must Not Upload

- `backend/.env`
- `backend/target/`
- `node_modules/`
- local logs
- Docker repair scripts or local registry backups
- real API keys, tokens, passwords, or private screenshots

## Should Upload

- `backend/src/`
- `backend/pom.xml`
- `backend/docker-compose.yml`
- `backend/.env.example`
- `backend/DEPLOYMENT_GUIDE.md`
- `backend/PROBLEM_LOG.md`
- `backend/AGENT_OPTIMIZATION_BACKLOG.md`
- `frontend/index.html`
- `frontend/styles.css`
- `frontend/app.js`
- `docs/`
- root `README.md`
- root `.gitignore`

## Suggested Git Commands

Install Git first if `git` is not available in PowerShell.

```powershell
cd <your-local-path>\do-not-miss-github
git init
git add .
git commit -m "Initial commit"
git branch -M main
git remote add origin https://github.com/<your-name>/<your-repo>.git
git push -u origin main
```

If you use GitHub Desktop, choose the `do-not-miss-github` folder as the local repository folder.
