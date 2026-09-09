# dev

dev 是纯部署环境，不做 checkout/build/push（镜像由 `test/` 共享构建job产出）。整体模型和字段说明见根目录 `README.md`。

`dev/setting.groovy` 的子字典只需要部署字段（`app_name`/`project`/端口/ingress/资源限制等），build 字段写在 `test/setting.groovy` 里。跑job时 `IMAGE_TAG` 参数填 `test` job打印出来的镜像tag。
