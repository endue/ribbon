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
package com.netflix.loadbalancer;

import com.netflix.client.config.IClientConfig;

/**
 * Given that
 * {@link IRule} can be cascaded, this {@link RetryRule} class allows adding a retry logic to an existing Rule.
 * 
 * @author stonse
 *
 * 这种方式其实是对轮询的扩展，轮询访问最多重试10次，而这种方式是在重试的时候增加了等待时间，默认500ms
 * 
 */
public class RetryRule extends AbstractLoadBalancerRule {
	// 子选择服务的规则：轮询
	IRule subRule = new RoundRobinRule();
	// 重新选择时的最大等待时间
	long maxRetryMillis = 500;

	public RetryRule() {
	}

	public RetryRule(IRule subRule) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public RetryRule(IRule subRule, long maxRetryMillis) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
		this.maxRetryMillis = (maxRetryMillis > 0) ? maxRetryMillis : 500;
	}

	public void setRule(IRule subRule) {
		this.subRule = (subRule != null) ? subRule : new RoundRobinRule();
	}

	public IRule getRule() {
		return subRule;
	}

	public void setMaxRetryMillis(long maxRetryMillis) {
		if (maxRetryMillis > 0) {
			this.maxRetryMillis = maxRetryMillis;
		} else {
			this.maxRetryMillis = 500;
		}
	}

	public long getMaxRetryMillis() {
		return maxRetryMillis;
	}

	
	
	@Override
	public void setLoadBalancer(ILoadBalancer lb) {		
		super.setLoadBalancer(lb);
		subRule.setLoadBalancer(lb);
	}

	/*
	 * Loop if necessary. Note that the time CAN be exceeded depending on the
	 * subRule, because we're not spawning additional threads and returning
	 * early.
	 */
	public Server choose(ILoadBalancer lb, Object key) {
		// 当前时间
		long requestTime = System.currentTimeMillis();
		// 最大等待时间
		long deadline = requestTime + maxRetryMillis;

		Server answer = null;
		// 子规则轮询选择一个服务
		answer = subRule.choose(key);
		// 选择服务为空或者非活跃，重新选择，最大等待时间500ms
		if (((answer == null) || (!answer.isAlive()))
				&& (System.currentTimeMillis() < deadline)) {
			// 新建任务，用来打断当前线程
			InterruptTask task = new InterruptTask(deadline
					- System.currentTimeMillis());

			while (!Thread.interrupted()) {
				answer = subRule.choose(key);

				if (((answer == null) || (!answer.isAlive()))
						&& (System.currentTimeMillis() < deadline)) {
					/* pause and retry hoping it's transient */
					Thread.yield();
				} else {
					break;
				}
			}

			task.cancel();
		}

		if ((answer == null) || (!answer.isAlive())) {
			return null;
		} else {
			return answer;
		}
	}

	@Override
	public Server choose(Object key) {
		return choose(getLoadBalancer(), key);
	}
}
