package io.openems.edge.deye.sun.hybrid.ess;

import io.openems.edge.common.channel.Doc;
import io.openems.edge.common.component.OpenemsComponent;
import io.openems.edge.deye.sun.hybrid.common.Deye;
import io.openems.edge.ess.api.SymmetricEss;

public interface DeyeEss extends Deye, SymmetricEss, OpenemsComponent {

	public static enum ChannelId implements io.openems.edge.common.channel.ChannelId {
		;
		private final Doc doc;

		private ChannelId(Doc doc) {
			this.doc = doc;
		}

		@Override
		public Doc doc() {
			return this.doc;
		}
	}

}
