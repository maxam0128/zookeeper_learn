# ZK 配置文件解析

## zk启动阶段对于配置文件的解析

配置文件解析是通过这个方法(QuorumPeerConfig#parse)实现的,下面看下具体实现：

```$xslt
public void parse(String path) throws ConfigException {
    LOG.info("Reading configuration from: " + path);
   
    try {
        // 这里通过VerifyingFileFactory来创建一个校验后的文件
        File configFile = (new VerifyingFileFactory.Builder(LOG)
            .warnForRelativePath()
            .failForNonExistingPath()
            .build()).create(path);
            
        Properties cfg = new Properties();
        FileInputStream in = new FileInputStream(configFile);
        try {
            cfg.load(in);
            configFileStr = path;
        } finally {
            in.close();
        }
        
        // 这里解析属性文件
        parseProperties(cfg);
    } catch (IOException e) {
        throw new ConfigException("Error processing " + path, e);
    } catch (IllegalArgumentException e) {
        throw new ConfigException("Error processing " + path, e);
    }   
    
    // 如果有动态配置文件，则执行动态配置属性文件解析
    if (dynamicConfigFileStr!=null) {
       try {           
           Properties dynamicCfg = new Properties();
           FileInputStream inConfig = new FileInputStream(dynamicConfigFileStr);
           try {
               dynamicCfg.load(inConfig);
               if (dynamicCfg.getProperty("version") != null) {
                   throw new ConfigException("dynamic file shouldn't have version inside");
               }

               String version = getVersionFromFilename(dynamicConfigFileStr);
               // If there isn't any version associated with the filename,
               // the default version is 0.
               if (version != null) {
                   dynamicCfg.setProperty("version", version);
               }
           } finally {
               inConfig.close();
           }
           setupQuorumPeerConfig(dynamicCfg, false);

       } catch (IOException e) {
           throw new ConfigException("Error processing " + dynamicConfigFileStr, e);
       } catch (IllegalArgumentException e) {
           throw new ConfigException("Error processing " + dynamicConfigFileStr, e);
       }        
       File nextDynamicConfigFile = new File(configFileStr + nextDynamicConfigFileSuffix);
       if (nextDynamicConfigFile.exists()) {
           try {           
               Properties dynamicConfigNextCfg = new Properties();
               FileInputStream inConfigNext = new FileInputStream(nextDynamicConfigFile);       
               try {
                   dynamicConfigNextCfg.load(inConfigNext);
               } finally {
                   inConfigNext.close();
               }
               boolean isHierarchical = false;
               for (Entry<Object, Object> entry : dynamicConfigNextCfg.entrySet()) {
                   String key = entry.getKey().toString().trim();  
                   if (key.startsWith("group") || key.startsWith("weight")) {
                       isHierarchical = true;
                       break;
                   }
               }
               lastSeenQuorumVerifier = createQuorumVerifier(dynamicConfigNextCfg, isHierarchical);
           } catch (IOException e) {
               LOG.warn("NextQuorumVerifier is initiated to null");
           }
       }
    }
}
```



