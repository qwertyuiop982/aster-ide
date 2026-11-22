import {tr} from '../locale.js';
export default {
  "id": "gemini",
  "label": "Gemini",
  "mark": "sparkles",
  "greeting": tr('从哪里开始？','Where should we start?'),
  "placeholder": tr('描述任务，或补充需要参考的内容…','Describe a task or add context…'),
  "navigation": false,
  "inspector": false,
  "starters": [
    [
      tr('理解项目','Understand the project'),
      "folder",
      tr('请说明这个项目的结构和关键流程。','Explain how this project is organized and how its main workflows operate.')
    ],
    [
      tr('分析问题','Analyze an issue'),
      "search",
      tr('请分析以下问题的原因：\n','Analyze what caused the following issue:\n')
    ],
    [
      tr('制定方案','Plan a change'),
      "list-checks",
      tr('请为以下需求制定实现方案：\n','Plan an implementation for the following requirements:\n')
    ],
    [
      tr('实现修改','Implement a change'),
      "code-xml",
      tr('请实现以下修改：\n','Implement the following changes:\n')
    ]
  ]
};
