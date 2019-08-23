[<img src=https://user-images.githubusercontent.com/6883670/31999264-976dfb86-b98a-11e7-9432-0316345a72ea.png height=75 />](https://reactome.org)

# Utils
Server side utilities shared across different projects

# How to use the ProxyServlet ?

Add the following block into the web.xml of your project if you need a specific path to be proxied.
e.g /templates/* will be proxied to http://site.reactome.org:80/templates

```html
<servlet>
    <servlet-name>Templates</servlet-name>
    <servlet-class>org.reactome.server.utils.proxy.ProxyServlet</servlet-class>
    <init-param>
        <param-name>proxyHost</param-name>
        <param-value>site.reactome.org</param-value>
    </init-param>
    <init-param>
        <param-name>proxyPort</param-name>
        <param-value>80</param-value>
    </init-param>
    <init-param>
        <param-name>proxyPath</param-name>
        <param-value>/templates</param-value>
    </init-param>
</servlet>
<servlet-mapping>
    <servlet-name>Templates</servlet-name>
    <url-pattern>/templates/*</url-pattern>
</servlet-mapping>
```

# How to use LruFolderContentChecker ?

This class checks if the size of a specific folder reaches the maximum size per every X time.
Parameters: path of the folder to be monitored
            maximum size
            threshold (how much space has to clean) 
            time (frequency of the checking, in seconds).
            ttl (time-to-live, in seconds)
            
```java
LruFolderContentChecker folderContentChecker = new LruFolderContentChecker(pathDirectory, maxSize, threshold, time, ttl);
folderContentChecker.addCheckerFileDeletedHandler(this);
```

```html
// In Spring Project with properties

<bean id="fileCheckerController" class="org.reactome.server.service.utils.JSONFileCheckerController" name="FileCheckerController" destroy-method="interrupt">
    <property name="pathDirectory" value="${json.custom.folder}"/>
    <property name="maxSize" value="2684354560"/> <!-- 2684354560 = 2.5GB // 5368709120 = 5 GB // 10737418240 = 10GB -->
    <property name="threshold" value="524288000"/> <!-- 10485760 = 10MB // 524288000 = 500MB // 1073741824 = 1GB -->
    <property name="time" value="10000"/> <!-- 10 sec -->
    <property name="ttl" value="604800000"/> <!-- 1 week -->
</bean>
```
