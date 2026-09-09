# prod

prod 独立构建+部署，不走 `test/` 共享构建。整体模型和字段说明见根目录 `README.md`。

`prod/setting.groovy` 的子字典要同时包含 build 字段（`github_url`/`project`/`project_type`/`maven_ins`等）和部署字段（`app_name`/端口/ingress/资源限制等），因为 prod 的 `astrox.Jenkinsfile` 自己做完整的 checkout+build+push+deploy。
