# HmRAGCLI CMD 离线部署命令清单

以下命令适用于 **Windows CMD**，不依赖 PowerShell。  
项目目录假设为：

```text
D:\workspace\HmRAGCLI
```

## 1. 进入项目目录

```cmd
cd /d D:\workspace\HmRAGCLI
```

## 2. 创建项目本地虚拟环境

如果 `.venv` 还不存在：

```cmd
python -m venv .venv
```

## 3. 激活虚拟环境

```cmd
.venv\Scripts\activate.bat
```

## 4. 联网机器安装基础依赖

```cmd
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install -r requirements.lock.txt
```

如果需要增强版 `Docling`：

```cmd
.venv\Scripts\python.exe -m pip install -r requirements.docling.lock.txt
```

## 5. 联网机器准备 wheelhouse

基础版：

```cmd
.venv\Scripts\python.exe -m pip download -r requirements.lock.txt -d wheelhouse
```

增强版：

```cmd
.venv\Scripts\python.exe -m pip download -r requirements.docling.lock.txt -d wheelhouse
```

## 6. 离线机器从 wheelhouse 安装

进入项目目录：

```cmd
cd /d D:\workspace\HmRAGCLI
```

创建虚拟环境：

```cmd
python -m venv .venv
```

安装基础版：

```cmd
.venv\Scripts\python.exe -m pip install --upgrade pip
.venv\Scripts\python.exe -m pip install --no-index --find-links wheelhouse -r requirements.lock.txt
```

如果要安装增强版：

```cmd
.venv\Scripts\python.exe -m pip install --no-index --find-links wheelhouse -r requirements.docling.lock.txt
```

## 7. 环境检查

基础版：

```cmd
.venv\Scripts\python.exe scripts\check_environment.py
```

增强版：

```cmd
.venv\Scripts\python.exe scripts\check_environment.py --with-docling
```

## 8. 初始化数据库

先进入后端目录：

```cmd
cd /d D:\workspace\HmRAGCLI\backend
```

执行初始化：

```cmd
D:\workspace\HmRAGCLI\.venv\Scripts\python.exe -c "from app.db.init_db import init_db; init_db(); print('init_db ok')"
```

## 9. 启动后端

在 `backend` 目录执行：

默认 8000 端口：

```cmd
D:\workspace\HmRAGCLI\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8000
```

如果要用 8010：

```cmd
D:\workspace\HmRAGCLI\.venv\Scripts\python.exe -m uvicorn app.main:app --host 0.0.0.0 --port 8010
```

说明：

上面这条命令会**前台运行服务**，不会自动退出回到命令行。  
这不是卡死，而是服务正在当前窗口里运行。

如果你想直接用脚本：

前台运行：

```cmd
scripts\start_backend.cmd
```

后台新窗口运行：

```cmd
scripts\start_backend_detached.cmd
```

## 10. 访问地址

运维面板：

```text
http://127.0.0.1:8010/ops
```

查询页面：

```text
http://127.0.0.1:8010/query
```

## 11. 最小离线测试顺序

1. 创建 `.venv`
2. 离线安装依赖
3. 运行环境检查
4. 初始化数据库
5. 启动后端
6. 打开 `/ops`
7. 新增数据源
8. 扫描
9. 入库
10. 打开 `/query` 验证检索结果和原始文件追踪
