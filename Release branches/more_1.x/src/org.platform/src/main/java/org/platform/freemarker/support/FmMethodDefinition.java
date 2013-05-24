/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.platform.freemarker.support;
import org.platform.context.AppContext;
import org.platform.freemarker.IFmMethod;
import com.google.inject.Provider;
import freemarker.template.TemplateMethodModel;
/**
 * 
 * @version : 2013-5-24
 * @author ������ (zyc@byshell.org)
 */
class FmMethodDefinition implements Provider<TemplateMethodModel> {
    private String               funName      = null;
    private Class<IFmMethod>     fmMethodType = null;
    private AppContext           appContext   = null;
    private InternalMethodObject funObject    = null;
    //
    public FmMethodDefinition(String funName, Class<IFmMethod> fmMethodType) {
        this.funName = funName;
        this.fmMethodType = fmMethodType;
    }
    public void initAppContext(AppContext appContext) {
        this.appContext = appContext;
    }
    public String getName() {
        return this.funName;
    }
    @Override
    public TemplateMethodModel get() {
        if (this.funObject == null)
            this.funObject = new InternalMethodObject(this.appContext.getInstance(this.fmMethodType), this.appContext);
        return this.funObject;
    }
}