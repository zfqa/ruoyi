package com.ruoyi.business.knowledge.domain;

import java.io.Serial;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 固定文件知识库及来源展示 实体
 * 
 * @author ruoyi
 */
public class KnowledgeBase extends BaseEntity
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @Excel(name = "主键", cellType = Excel.ColumnType.NUMERIC)
    private Long id;

    /** 固定资料编码 */
    @Excel(name = "资料编码")
    private String sourceCode;

    /** 资料名称 */
    @Excel(name = "资料名称")
    private String sourceName;

    /** 来源类型：PDF/NEWS/POLICY/REPORT */
    @Excel(name = "来源类型")
    private String sourceType;

    /** 当前有效版本 */
    private Long currentVersionId;

    /** 归属部门 */
    private Long ownerDeptId;

    /** 密级 */
    @Excel(name = "密级")
    private String confidentiality;

    /** 允许使用范围 */
    @Excel(name = "允许使用范围")
    private String allowedPurpose;

    /** 允许访问角色，逗号分隔 */
    private String allowedRoleIds;

    /** 是否启用 */
    @Excel(name = "是否启用", readConverterExp = "0=否,1=是")
    private String enabled;

    /** 状态（0待处理 1处理中 2成功 3失败） */
    @Excel(name = "状态", readConverterExp = "0=待处理,1=处理中,2=成功,3=失败")
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceCode() { return sourceCode; }
    public void setSourceCode(String sourceCode) { this.sourceCode = sourceCode; }

    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public Long getCurrentVersionId() { return currentVersionId; }
    public void setCurrentVersionId(Long currentVersionId) { this.currentVersionId = currentVersionId; }

    public Long getOwnerDeptId() { return ownerDeptId; }
    public void setOwnerDeptId(Long ownerDeptId) { this.ownerDeptId = ownerDeptId; }

    public String getConfidentiality() { return confidentiality; }
    public void setConfidentiality(String confidentiality) { this.confidentiality = confidentiality; }

    public String getAllowedPurpose() { return allowedPurpose; }
    public void setAllowedPurpose(String allowedPurpose) { this.allowedPurpose = allowedPurpose; }

    public String getAllowedRoleIds() { return allowedRoleIds; }
    public void setAllowedRoleIds(String allowedRoleIds) { this.allowedRoleIds = allowedRoleIds; }

    public String getEnabled() { return enabled; }
    public void setEnabled(String enabled) { this.enabled = enabled; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("sourceCode", getSourceCode())
            .append("sourceName", getSourceName())
            .append("sourceType", getSourceType())
            .append("currentVersionId", getCurrentVersionId())
            .append("ownerDeptId", getOwnerDeptId())
            .append("confidentiality", getConfidentiality())
            .append("allowedPurpose", getAllowedPurpose())
            .append("allowedRoleIds", getAllowedRoleIds())
            .append("enabled", getEnabled())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
