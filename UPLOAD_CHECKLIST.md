# GitHub Upload Checklist

## 必须确认不要上传

- `backend/.env`
- `backend/target/`
- `frontend/node_modules/`
- `frontend/dist/`
- `*.log`
- 本地录屏、截图、压缩包、私有脚本
- 真实 API Key、Token、密码、Cookie

## 应该上传

- `backend/src/`
- `backend/pom.xml`
- `backend/docker-compose.yml`
- `backend/.env.example`
- `backend/DEPLOYMENT_GUIDE.md`
- `backend/PROBLEM_LOG.md`
- `frontend/src/`
- `frontend/package.json`
- `frontend/package-lock.json`
- `frontend/index.html`
- `frontend/vite.config.ts`
- `docs/`
- 根目录 `README.md`
- 根目录 `.gitignore`

## GitHub Desktop

如果你用 GitHub Desktop：

1. 打开本地仓库 `D:\warma\Documents\do-not-miss-github`。
2. 确认 Changes 里没有 `.env`、`target`、`node_modules`、`dist`。
3. 填写提交信息。
4. Commit to main。
5. Push origin。

## 命令行

如果你用命令行：

```powershell
cd D:\warma\Documents\do-not-miss-github
git status
git add .
git commit -m "Update Do Not Miss project"
git push
```
