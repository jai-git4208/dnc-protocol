package com.ivelosi.dncprotocol.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.ivelosi.dncprotocol.databinding.FragmentDiagnosticsBinding
import com.ivelosi.dncprotocol.utils.WifiAwareDiagnostics
import kotlinx.coroutines.launch

class DiagnosticsFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!

    private lateinit var wifiAwareDiagnostics: WifiAwareDiagnostics

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        wifiAwareDiagnostics = WifiAwareDiagnostics(requireContext())

        binding.buttonRunDiagnostics.setOnClickListener {
            binding.textDiagnostics.text = "Running diagnostics..."
            binding.progressBar.visibility = View.VISIBLE

            lifecycleScope.launch {
                val results = wifiAwareDiagnostics.runDiagnostics()
                binding.textDiagnostics.text = results
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}