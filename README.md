## Apache-Tomcat-9.0.X

apache-tomcat默认使用Ant编译,，这里更换为Maven编译，同时做了以下处理：

- 添加[pom.xml](pom.xml)

- 新建home目录, 将源码目录的`/conf`和`/webapps`移动到home目录下；

- 标记目录`/java`为源码目录，去掉`/test`测试目录；

- 启动类`org.apache.catalina.startup.Bootstrap`添加如下代码, 设置catalina的目录
    ```java
     System.setProperty(Constants.CATALINA_HOME_PROP, userDir.concat("/home"));
    ```

- `org.apache.catalina.startup.ContextConfig`类的 configureStart() 方法，添加初始化 JSP 解析器的代码:

  ```java
  context.addServletContainerInitializer(new JasperInitializer(), null);
  ```

- 日志乱码处理(java读取文件的默认格式是iso8859-1, 而tomcat中文存储的时候一般是UTF-8，所以导致读出来的是乱码)，需要修改的代码位置：

  - `org.apache.tomcat.util.res.StringManager#getString(final String key, final Object... args)`
  - `org.apache.jasper.compiler.Localizer#getMessage(String errCode)`

  增加如下的编码处理：

  ```java
  try {
      value = new String(value.getBytes(StandardCharsets.ISO_8859_1), 
                         StandardCharsets.UTF_8);
  } catch(Exception e){
      e.printStackTrace();
  }
  ```
