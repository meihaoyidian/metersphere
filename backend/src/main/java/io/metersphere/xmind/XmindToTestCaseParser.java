package io.metersphere.xmind;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import io.metersphere.base.domain.TestCaseWithBLOBs;
import io.metersphere.commons.constants.TestCaseConstants;
import io.metersphere.commons.utils.BeanUtils;
import io.metersphere.commons.utils.LogUtil;
import io.metersphere.excel.domain.TestCaseExcelData;
import io.metersphere.i18n.Translator;
import io.metersphere.track.service.TestCaseService;
import io.metersphere.xmind.parser.XmindParser;
import io.metersphere.xmind.parser.domain.Attached;
import io.metersphere.xmind.parser.domain.JsonRootBean;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据转换
 * 1 解析Xmind文件 XmindParser.parseJson
 * 2 解析后的JSON 转成测试用例
 */
public class XmindToTestCaseParser {

    private TestCaseService testCaseService;
    private String maintainer;
    private String projectId;
    private StringBuffer process; // 过程校验记录
    private Set<String> testCaseNames;

    public XmindToTestCaseParser(TestCaseService testCaseService, String userId, String projectId, Set<String> testCaseNames) {
        this.testCaseService = testCaseService;
        this.maintainer = userId;
        this.projectId = projectId;
        this.testCaseNames = testCaseNames;
        testCaseWithBLOBs = new LinkedList<>();
        xmindDataList = new ArrayList<>();
        process = new StringBuffer();
    }

    // 案例详情
    private List<TestCaseWithBLOBs> testCaseWithBLOBs;
    // 用于重复对比
    protected List<TestCaseExcelData> xmindDataList;

    // 递归处理案例数据
    private void makeXmind(StringBuffer processBuffer, Attached parent, int level, String nodePath, List<Attached> attacheds) {
        for (Attached item : attacheds) {
            if (isBlack(item.getTitle(), "(?:tc|tc)")) { // 用例
                item.setParent(parent);
                this.newTestCase(item.getTitle(), parent.getPath(), item.getChildren() != null ? item.getChildren().getAttached() : null);
            } else {
                nodePath = parent.getPath() + "/" + item.getTitle();
                item.setPath(nodePath);
                if (item.getChildren() != null && !item.getChildren().getAttached().isEmpty()) {
                    item.setParent(parent);
                    makeXmind(processBuffer, item, level + 1, nodePath, item.getChildren().getAttached());
                }
            }
        }
    }

    private boolean isBlack(String str, String regex) {
        // regex = "(?:tc:|tc：)"
        if (StringUtils.isEmpty(str) || StringUtils.isEmpty(regex))
            return false;
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher result = pattern.matcher(str);
        return result.find();
    }

    private String replace(String str, String regex) {
        if (StringUtils.isEmpty(str) || StringUtils.isEmpty(regex))
            return str;
        Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        Matcher result = pattern.matcher(str);
        str = result.replaceAll("");
        return str;
    }

    // 获取步骤数据
    public String getSteps(List<Attached> attacheds) {
        JSONArray jsonArray = new JSONArray();
        for (int i = 0; i < attacheds.size(); i++) {
            // 保持插入顺序，判断用例是否有相同的steps
            JSONObject step = new JSONObject(true);
            step.put("num", i + 1);
            step.put("desc", attacheds.get(i).getTitle());
            if (attacheds.get(i).getChildren() != null && !attacheds.get(i).getChildren().getAttached().isEmpty()) {
                step.put("result", attacheds.get(i).getChildren().getAttached().get(0).getTitle());
            }
            jsonArray.add(step);
        }
        return jsonArray.toJSONString();
    }

    // 初始化一个用例
    private void newTestCase(String title, String nodePath, List<Attached> attacheds) {
        TestCaseWithBLOBs testCase = new TestCaseWithBLOBs();
        testCase.setProjectId(projectId);
        testCase.setMaintainer(maintainer);
        testCase.setPriority("P0");
        testCase.setMethod("manual");
        testCase.setType("functional");

        String tc = title.replace("：", ":");
        String tcArr[] = tc.split(":");
        if (tcArr.length != 2) {
            process.append(Translator.get("test_case_name") + "【 " + title + " 】" + Translator.get("incorrect_format"));
            return;
        }
        // 用例名称
        testCase.setName(this.replace(tcArr[1], "tc:|tc：|tc"));

        if (!nodePath.startsWith("/")) {
            nodePath = "/" + nodePath;
        }
        if (nodePath.endsWith("/")) {
            nodePath = nodePath.substring(0, nodePath.length() - 1);
        }
        testCase.setNodePath(nodePath);

        // 用例等级和用例性质处理
        if (tcArr[0].indexOf("-") != -1) {
            String otArr[] = tcArr[0].split("-");
            for (int i = 0; i < otArr.length; i++) {
                if (otArr[i].startsWith("P") || otArr[i].startsWith("p")) {
                    testCase.setPriority(otArr[i].toUpperCase());
                } else if (otArr[i].endsWith("功能测试")) {
                    testCase.setType("functional");
                } else if (otArr[i].endsWith("性能测试")) {
                    testCase.setType("performance");
                } else if (otArr[i].endsWith("接口测试")) {
                    testCase.setType("api");
                }
            }
        }
        // 测试步骤处理
        List<Attached> steps = new LinkedList<>();
        if (attacheds != null && !attacheds.isEmpty()) {
            attacheds.forEach(item -> {
                if (isBlack(item.getTitle(), "(?:pc:|pc：)")) {
                    testCase.setPrerequisite(replace(item.getTitle(), "(?:pc:|pc：)"));
                } else if (isBlack(item.getTitle(), "(?:rc:|rc：)")) {
                    testCase.setRemark(replace(item.getTitle(), "(?:rc:|rc：)"));
                } else {
                    steps.add(item);
                }
            });
        }
        if (!steps.isEmpty()) {
            testCase.setSteps(this.getSteps(steps));
        } else {
            JSONArray jsonArray = new JSONArray();
            // 保持插入顺序，判断用例是否有相同的steps
            JSONObject step = new JSONObject(true);
            step.put("num", 1);
            step.put("desc", "");
            step.put("result", "");
            jsonArray.add(step);
            testCase.setSteps(jsonArray.toJSONString());
        }
        TestCaseExcelData compartData = new TestCaseExcelData();
        BeanUtils.copyBean(compartData, testCase);
        if (xmindDataList.contains(compartData)) {
            process.append(Translator.get("test_case_already_exists_excel") + "：" + testCase.getName() + "; ");
        } else if (validate(testCase)) {
            testCase.setId(UUID.randomUUID().toString());
            testCase.setCreateTime(System.currentTimeMillis());
            testCase.setUpdateTime(System.currentTimeMillis());
            testCaseWithBLOBs.add(testCase);
        }
        xmindDataList.add(compartData);
    }

    //获取流文件
    private static void inputStreamToFile(InputStream ins, File file) {
        try (OutputStream os = new FileOutputStream(file);) {
            int bytesRead = 0;
            byte[] buffer = new byte[8192];
            while ((bytesRead = ins.read(buffer, 0, 8192)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
        } catch (Exception e) {
            LogUtil.error(e.getMessage());
        }
    }

    /**
     * MultipartFile 转 File
     *
     * @param file
     * @throws Exception
     */
    private File multipartFileToFile(MultipartFile file) throws Exception {
        if (file != null && file.getSize() > 0) {
            try (InputStream ins = file.getInputStream();) {
                File toFile = new File(file.getOriginalFilename());
                inputStreamToFile(ins, toFile);
                return toFile;
            }
        }
        return null;
    }


    public boolean validate(TestCaseWithBLOBs data) {
        String nodePath = data.getNodePath();
        StringBuilder stringBuilder = new StringBuilder();

        if (nodePath != null) {
            String[] nodes = nodePath.split("/");
            if (nodes.length > TestCaseConstants.MAX_NODE_DEPTH + 1) {
                stringBuilder.append(Translator.get("test_case_node_level_tip") +
                        TestCaseConstants.MAX_NODE_DEPTH + Translator.get("test_case_node_level") + "; ");
            }
            for (int i = 0; i < nodes.length; i++) {
                if (i != 0 && org.apache.commons.lang3.StringUtils.equals(nodes[i].trim(), "")) {
                    stringBuilder.append(Translator.get("module_not_null") + "; ");
                    break;
                }
            }
        }

        if (org.apache.commons.lang3.StringUtils.equals(data.getType(), TestCaseConstants.Type.Functional.getValue()) && org.apache.commons.lang3.StringUtils.equals(data.getMethod(), TestCaseConstants.Method.Auto.getValue())) {
            stringBuilder.append(Translator.get("functional_method_tip") + "; ");
        }

        if (testCaseNames.contains(data.getName())) {
            boolean dbExist = testCaseService.exist(data);
            boolean excelExist = false;

            if (dbExist) {
                // db exist
                stringBuilder.append(Translator.get("test_case_already_exists_excel") + "：" + data.getName() + "; ");
            }

        } else {
            testCaseNames.add(data.getName());
        }
        if (!StringUtils.isEmpty(stringBuilder.toString())) {
            process.append(stringBuilder.toString());
            return false;
        }
        return true;
    }

    // 导入思维导图处理
    public String importXmind(MultipartFile multipartFile) {
        StringBuffer processBuffer = new StringBuffer();
        File file = null;
        try {
            file = multipartFileToFile(multipartFile);
            if (file == null || !file.exists())
                return Translator.get("incorrect_format");

            // 获取思维导图内容
            String content = XmindParser.parseJson(file);
            if (StringUtils.isEmpty(content) || content.split("(?:tc:|tc：|TC:|TC：|tc|TC)").length == 1) {
                return Translator.get("import_xmind_not_found");
            }
            if (!StringUtils.isEmpty(content) && content.split("(?:tc:|tc：|TC:|TC：|tc|TC)").length > 500) {
                return Translator.get("import_xmind_count_error");
            }
            JsonRootBean root = JSON.parseObject(content, JsonRootBean.class);

            if (root != null && root.getRootTopic() != null && root.getRootTopic().getChildren() != null) {
                // 判断是模块还是用例
                for (Attached item : root.getRootTopic().getChildren().getAttached()) {
                    if (isBlack(item.getTitle(), "(?:tc:|tc：|tc)")) { // 用例
                        return replace(item.getTitle(), "(?:tc:|tc：|tc)") + "：" + Translator.get("test_case_create_module_fail");
                    } else {
                        item.setPath(item.getTitle());
                        if (item.getChildren() != null && !item.getChildren().getAttached().isEmpty()) {
                            item.setPath(item.getTitle());
                            makeXmind(processBuffer, item, 1, item.getPath(), item.getChildren().getAttached());
                        }
                    }
                }
            }
            if (StringUtils.isEmpty(process.toString()) && !testCaseWithBLOBs.isEmpty()) {
                testCaseService.saveImportData(testCaseWithBLOBs, projectId);
            }
        } catch (Exception ex) {
            processBuffer.append(Translator.get("incorrect_format"));
            LogUtil.error(ex.getMessage());
            ex.printStackTrace();
        } finally {
            if (file != null)
                file.delete();
            testCaseWithBLOBs.clear();
        }
        return process.toString();
    }
}
