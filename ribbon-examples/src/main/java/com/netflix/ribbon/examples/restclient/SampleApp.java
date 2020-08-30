/*
*
* Copyright 2013 Netflix, Inc.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*
*/

package com.netflix.ribbon.examples.restclient;

import java.net.URI;

import com.netflix.client.ClientFactory;
import com.netflix.client.http.HttpRequest;
import com.netflix.client.http.HttpResponse;
import com.netflix.config.ConfigurationManager;
import com.netflix.loadbalancer.ZoneAwareLoadBalancer;
import com.netflix.niws.client.http.RestClient;

public class SampleApp {
	public static void main(String[] args) throws Exception {
	    // 读取配置文件
        ConfigurationManager.loadPropertiesFromResources("sample-client.properties");  // 1
        // 打印服务列表
        System.out.println(ConfigurationManager.getConfigInstance().getProperty("sample-client.ribbon.listOfServers"));
        // 传入服务标识名称为sample-client
        // 之后根据服务标识和ClientFactory创建其对应的client和ILoadBalancer
        RestClient client = (RestClient) ClientFactory.getNamedClient("sample-client");  // 2
        // 构造请求
        HttpRequest request = HttpRequest.newBuilder().uri(new URI("/")).build(); // 3
        for (int i = 0; i < 2; i++)  {
        	HttpResponse response = client.executeWithLoadBalancer(request); // 4
        	System.out.println("Status code for " + response.getRequestedURI() + "  :" + response.getStatus());
        }
       /* @SuppressWarnings("rawtypes")
        ZoneAwareLoadBalancer lb = (ZoneAwareLoadBalancer) client.getLoadBalancer();
        // 打印LoadBalancerStats中内容
        System.out.println(lb.getLoadBalancerStats());
        // 变更服务列表
        ConfigurationManager.getConfigInstance().setProperty("sample-client.ribbon.listOfServers", "http://localhost:8762/eureka,http://localhost:8764/eureka"); // 5
        System.out.println("changing servers ...");
        Thread.sleep(3000); // 6
        for (int i = 0; i < 6; i++)  {
            HttpResponse response = null;
            try {
        	    response = client.executeWithLoadBalancer(request);
        	    System.out.println("Status code for " + response.getRequestedURI() + "  : " + response.getStatus());
            } finally {
                if (response != null) {
                    response.close();
                }
            }
        }
        System.out.println(lb.getLoadBalancerStats()); // 7*/
	}
}
