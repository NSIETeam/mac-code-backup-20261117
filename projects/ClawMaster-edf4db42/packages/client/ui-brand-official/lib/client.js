window.__ModuleLoader__.load({
	id: "@deepseek-ai/dsh-client-ui-brand-official",
	factory: (require) => {
		var module = { exports: {} };
		var exports = module.exports;
		Object.defineProperty(exports, Symbol.toStringTag, { value: "Module" });
		require("react/jsx-runtime");
		require("@deepseek-ai/dsh-client-ui-primitives");
		//#region lib/types/client/index.js
		/** Required service: the UI slot registry. */
		const inject = ["slots"];
		/**
		* Fill the sidebar brand slots as one declaration-aware registration set. The
		* conversation hero stays on its declaring package's animated fish fallback,
		* so the official build registers nothing there.
		* @param ctx - Client root context.
		*/
		function apply(ctx) {}
		//#endregion
		exports.apply = apply;
		exports.inject = inject;
		return module.exports;
	}
});

//# sourceMappingURL=client.js.map