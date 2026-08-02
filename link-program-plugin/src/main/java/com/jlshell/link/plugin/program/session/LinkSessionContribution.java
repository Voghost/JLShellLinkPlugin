package com.jlshell.link.plugin.program.session;

import com.jlshell.plugin.api.PluginContext;
import com.jlshell.plugin.api.session.ProgramSessionContribution;
import com.jlshell.plugin.api.session.ProgramSessionController;

/** JLShell Link Program 插件在单个 SSH 会话中的功能入口。 */
public final class LinkSessionContribution implements ProgramSessionContribution {

    @Override public String displayName() { return "JLShell Link"; }

    @Override
    public String description() {
        return "Deploy an Agent and open encrypted SSH/TCP tunnels for this session.";
    }

    @Override
    public ProgramSessionController activate(PluginContext context) {
        return new LinkSessionController(context);
    }
}
