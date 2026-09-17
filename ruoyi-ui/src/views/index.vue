<template>
  <div class="app-container home">
    <div class="home-header">
      <h2>功能一览</h2>
      <p>按业务模块快速进入常用功能</p>
    </div>

    <div v-for="group in featureGroups" :key="group.title" class="feature-group">
      <div class="group-title">{{ group.title }}</div>
      <el-row :gutter="16">
        <el-col
          v-for="item in group.items"
          :key="item.path"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
        >
          <div class="feature-card" @click="goPage(item.path)">
            <div class="feature-icon">
              <svg-icon :icon-class="item.icon" />
            </div>
            <div class="feature-body">
              <div class="feature-name">{{ item.name }}</div>
              <div class="feature-desc">{{ item.desc }}</div>
            </div>
            <i class="el-icon-arrow-right feature-arrow" />
          </div>
        </el-col>
      </el-row>
    </div>
  </div>
</template>

<script>
export default {
  name: "Index",
  data() {
    return {
      featureGroups: [
        {
          title: "数据接入与解析",
          items: [
            { name: "Excel解析", path: "/business/data/excel", icon: "excel", desc: "Excel/CSV 解析、字段识别与质量检查" },
            { name: "PDF解析", path: "/business/data/pdf", icon: "pdf", desc: "PDF 文档解析与内容提取" },
            { name: "文本结构化", path: "/business/data/text", icon: "edit", desc: "非结构化文本抽取与结构化入库" }
          ]
        },
        {
          title: "市场分析",
          items: [
            { name: "整车市场分析", path: "/business/analysis/vehicle/market", icon: "chart", desc: "行业资料门禁、周报生成与可视化" },
            { name: "车载市场分析", path: "/business/analysis/display/onboard", icon: "monitor", desc: "自由选取文件并生成车载分析报告" }
          ]
        },
        {
          title: "采集与知识",
          items: [
            { name: "新闻采集", path: "/business/news/collect", icon: "download", desc: "行业新闻采集与管理" },
            { name: "懂车帝车辆采集", path: "/business/news/vehicle", icon: "shopping", desc: "车型参数采集并入库统一知识库" },
            { name: "固定知识库", path: "/business/knowledge", icon: "education", desc: "知识检索、问答与图谱能力" }
          ]
        },
        {
          title: "AI 能力",
          items: [
            { name: "AI分析报告", path: "/business/report", icon: "documentation", desc: "查看与管理已生成的分析报告" },
            { name: "AI配置", path: "/business/ai", icon: "tool", desc: "统一大模型凭证、模型与作用范围" }
          ]
        }
      ]
    }
  },
  methods: {
    goPage(path) {
      this.$router.push(path)
    }
  }
}
</script>

<style scoped lang="scss">
.home {
  .home-header {
    margin-bottom: 24px;

    h2 {
      margin: 0 0 8px;
      font-size: 22px;
      font-weight: 600;
      color: #303133;
    }

    p {
      margin: 0;
      font-size: 14px;
      color: #909399;
    }
  }

  .feature-group {
    margin-bottom: 28px;
  }

  .group-title {
    margin-bottom: 12px;
    font-size: 15px;
    font-weight: 600;
    color: #606266;
  }

  .feature-card {
    display: flex;
    align-items: center;
    gap: 12px;
    min-height: 88px;
    margin-bottom: 16px;
    padding: 16px;
    border: 1px solid #ebeef5;
    border-radius: 8px;
    background: #fff;
    cursor: pointer;
    transition: border-color 0.2s, box-shadow 0.2s;

    &:hover {
      border-color: #409eff;
      box-shadow: 0 2px 12px rgba(64, 158, 255, 0.12);

      .feature-arrow {
        color: #409eff;
      }
    }
  }

  .feature-icon {
    flex-shrink: 0;
    width: 40px;
    height: 40px;
    display: flex;
    align-items: center;
    justify-content: center;
    border-radius: 8px;
    background: #ecf5ff;
    color: #409eff;
    font-size: 20px;
  }

  .feature-body {
    flex: 1;
    min-width: 0;
  }

  .feature-name {
    margin-bottom: 4px;
    font-size: 15px;
    font-weight: 600;
    color: #303133;
  }

  .feature-desc {
    font-size: 12px;
    line-height: 1.5;
    color: #909399;
  }

  .feature-arrow {
    flex-shrink: 0;
    color: #c0c4cc;
    font-size: 14px;
  }
}
</style>
