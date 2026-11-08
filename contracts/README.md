# 评分结果契约说明

`grade-result.schema.json` 是应用本地的完整校验契约，不是所有供应商都支持的请求 Schema。适配器要根据模型支持的 JSON Object/JSON Schema 子集生成请求；本地仍执行完整校验。

Schema 只能约束格式。下列业务规则必须由 Java 根据当前 QuestionConfig 再验证：

1. criteria 的 ID 集合与配置恰好一致，不能增减、重复或更名。
2. 每项 awarded 属于该项 allowedScores，且不超过 maxScore。
3. 配置满分等于评分点满分之和；scoreStep 合法。
4. 用 BigDecimal 读取、计算与格式化，不通过 double 中转。
5. status=UNABLE 时不产生可提交总分；所有 awarded=null。
6. status=SCORED 时分项合法且 issues 为空；总分由 Java 产生，不接受模型总分字段。
7. 请求上下文、实际模型、runId、captureId 由本地绑定，不由模型提供可信标识。
8. 评分依据是模型输出的待评估解释，不是独立证明，也不是“置信正确率”。

examples 中的两份 grade-result.synthetic 文件只用于格式与业务校验测试，不是对用户上传的手写答案作出的判分，也不是模型实测结果。

输出超限、拒绝、截断、传输失败或结构不符必须进入异常处理；禁止通过正则从任意回复中抓一个数字直接提交。
